package org.mpmc.coordinator.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.mpmc.protocol.B64Url
import org.mpmc.protocol.Digest
import org.mpmc.protocol.Ed25519
import org.mpmc.protocol.EndpointId
import org.mpmc.protocol.JwkKey
import org.mpmc.protocol.TicketCodec
import org.mpmc.protocol.TicketPayload
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.UUID

data class AccessToken(val value: String, val expiresAt: Instant)

class SigningKeys private constructor(
    val kid: String,
    private val privateKey: PrivateKey,
    val rawPublicKey: ByteArray,
) {
    fun sign(message: ByteArray): ByteArray = Ed25519.sign(privateKey, message)

    fun verify(message: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(rawPublicKey, message, signature)

    fun issueTicket(payload: TicketPayload): String = TicketCodec.encode(payload, ::sign)

    fun jwk(): JwkKey = JwkKey(kty = "OKP", crv = "Ed25519", kid = kid, x = B64Url.encode(rawPublicKey))

    fun issueAccessToken(endpointId: String, now: Instant, ttlSeconds: Long): AccessToken {
        val issuedAt = now.epochSecond
        val expiresAt = issuedAt + ttlSeconds
        val jti = UUID.randomUUID().toString()
        val header = """{"alg":"EdDSA","kid":"$kid","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("sub", endpointId)
            put("jti", jti)
            put("iat", issuedAt)
            put("exp", expiresAt)
        }
        val signingInput = "${B64Url.encodeUtf8(header)}.${B64Url.encodeUtf8(claims.toString())}"
        val signature = sign(signingInput.toByteArray(Charsets.US_ASCII))
        return AccessToken("$signingInput.${B64Url.encode(signature)}", Instant.ofEpochSecond(expiresAt))
    }

    fun verifyAccessToken(token: String, now: Instant): String? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val signature = runCatching { B64Url.decode(parts[2]) }.getOrNull() ?: return null
        if (!verify("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII), signature)) return null

        val header = runCatching {
            json.parseToJsonElement(B64Url.decodeUtf8(parts[0])).jsonObject
        }.getOrNull() ?: return null
        if (header["alg"]?.jsonPrimitive?.contentOrNull != "EdDSA") return null
        if (header["kid"]?.jsonPrimitive?.contentOrNull != kid) return null

        val claims = runCatching {
            json.parseToJsonElement(B64Url.decodeUtf8(parts[1])).jsonObject
        }.getOrNull() ?: return null
        val expiresAt = claims["exp"]?.jsonPrimitive?.longOrNull ?: return null
        if (expiresAt <= now.epochSecond) return null
        val subject = claims["sub"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!EndpointId.isValid(subject)) return null
        return subject
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun kidFor(rawPublicKey: ByteArray): String = Digest.sha256Hex(rawPublicKey).take(16)

        fun loadOrCreate(path: Path): SigningKeys {
            val absolute = path.toAbsolutePath().normalize()
            if (!Files.exists(absolute)) {
                absolute.parent?.let { Files.createDirectories(it) }
                val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                Files.write(absolute, pair.private.encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                Files.write(
                    Path.of("$absolute.pub"),
                    pair.public.encoded,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
            }
            return load(absolute)
        }

        fun load(path: Path): SigningKeys {
            val absolute = path.toAbsolutePath().normalize()
            val factory = KeyFactory.getInstance("Ed25519")
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(absolute)))
            val publicKeyPath = Path.of("$absolute.pub")
            require(Files.exists(publicKeyPath)) { "missing public key file: $publicKeyPath" }
            val publicKey = factory.generatePublic(X509EncodedKeySpec(Files.readAllBytes(publicKeyPath)))
            val raw = Ed25519.rawFromPublic(publicKey)
            return SigningKeys(kidFor(raw), privateKey, raw)
        }
    }
}
