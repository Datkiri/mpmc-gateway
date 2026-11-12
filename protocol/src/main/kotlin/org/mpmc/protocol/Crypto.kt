package org.mpmc.protocol

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object B64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(text: String): ByteArray = decoder.decode(text)
    fun encodeUtf8(text: String): String = encode(text.toByteArray(Charsets.UTF_8))
    fun decodeUtf8(text: String): String = String(decode(text), Charsets.UTF_8)
}

object Digest {
    private const val HEX = "0123456789abcdef"

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun fromHexOrNull(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

object EndpointId {
    fun isValid(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

object ConstantTime {
    fun equals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun equalsDigest(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(sha256Digest(a), sha256Digest(b))

    private fun sha256Digest(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
}

object Ed25519 {
    private const val ALGORITHM = "Ed25519"
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    private val PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
        0x04, 0x22, 0x04, 0x20,
    )

    fun publicFromRaw(raw: ByteArray): PublicKey {
        require(raw.size == 32) { "ed25519 raw public key must be 32 bytes" }
        val spec = X509EncodedKeySpec(SPKI_PREFIX + raw)
        return KeyFactory.getInstance(ALGORITHM).generatePublic(spec)
    }

    fun privateFromPkcs8(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun privateFromSeed(seed: ByteArray): PrivateKey {
        require(seed.size == 32) { "ed25519 seed must be 32 bytes" }
        return privateFromPkcs8(PKCS8_PREFIX + seed)
    }


    fun rawFromPublic(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        require(encoded.size == 44) { "unexpected ed25519 SPKI length: ${encoded.size}" }
        return encoded.copyOfRange(12, 44)
    }

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    fun verify(rawPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(publicFromRaw(rawPublicKey))
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
}

object Tokens {
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun randomB64Url(size: Int = 32): String = B64Url.encode(randomBytes(size))

    fun randomHex(size: Int = 32): String = Digest.hex(randomBytes(size))
}
