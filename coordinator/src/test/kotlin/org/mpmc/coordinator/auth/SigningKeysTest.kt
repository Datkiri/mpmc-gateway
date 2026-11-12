package org.mpmc.coordinator.auth

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mpmc.protocol.B64Url
import org.mpmc.protocol.Tokens
import java.nio.file.Path
import java.time.Instant

class SigningKeysTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val endpointId = Tokens.randomHex(32)

    @Test
    fun `loadOrCreate is stable across reloads`() {
        val path = tempDir.resolve("signing.key")
        val first = SigningKeys.loadOrCreate(path)
        val second = SigningKeys.loadOrCreate(path)

        assertEquals(first.kid, second.kid)
        assertArrayEquals(first.rawPublicKey, second.rawPublicKey)
        assertEquals(16, first.kid.length)
        val message = "hello".toByteArray()
        assertEquals(true, second.verify(message, first.sign(message)))
    }

    @Test
    fun `access token round trips and expires`() {
        val keys = SigningKeys.loadOrCreate(tempDir.resolve("signing.key"))
        val token = keys.issueAccessToken(endpointId, now, 900)

        assertEquals(endpointId, keys.verifyAccessToken(token.value, now))
        assertEquals(endpointId, keys.verifyAccessToken(token.value, now.plusSeconds(899)))
        assertNull(keys.verifyAccessToken(token.value, now.plusSeconds(900)))
        assertEquals(now.plusSeconds(900), token.expiresAt)
    }

    @Test
    fun `tampered access token is rejected`() {
        val keys = SigningKeys.loadOrCreate(tempDir.resolve("signing.key"))
        val token = keys.issueAccessToken(endpointId, now, 900)
        val parts = token.value.split('.')
        val signature = B64Url.decode(parts[2])
        signature[0] = (signature[0].toInt() xor 0x01).toByte()
        val tampered = "${parts[0]}.${parts[1]}.${B64Url.encode(signature)}"

        assertNull(keys.verifyAccessToken(tampered, now))
        assertNull(keys.verifyAccessToken("not-a-jwt", now))
    }

    @Test
    fun `jwk exposes raw public key`() {
        val keys = SigningKeys.loadOrCreate(tempDir.resolve("signing.key"))
        val jwk = keys.jwk()

        assertEquals("OKP", jwk.kty)
        assertEquals("Ed25519", jwk.crv)
        assertEquals(keys.kid, jwk.kid)
        assertArrayEquals(keys.rawPublicKey, B64Url.decode(jwk.x))
        assertNotNull(jwk.x)
    }
}
