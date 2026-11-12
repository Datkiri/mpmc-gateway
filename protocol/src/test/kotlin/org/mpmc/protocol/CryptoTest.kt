package org.mpmc.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator

class CryptoTest {
    @Test
    fun `sha256 matches known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Digest.sha256Hex("abc"),
        )
    }

    @Test
    fun `base64url has no padding and round-trips`() {
        val encoded = B64Url.encodeUtf8("hello mpmc")
        assertFalse(encoded.contains('='))
        assertEquals("hello mpmc", B64Url.decodeUtf8(encoded))
    }

    @Test
    fun `ed25519 raw public key round-trips and signs`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = Ed25519.rawFromPublic(pair.public)
        assertEquals(32, raw.size)
        assertArrayEquals(pair.public.encoded.copyOfRange(12, 44), raw)

        val message = "mpmc".toByteArray()
        val signature = Ed25519.sign(pair.private, message)
        assertTrue(Ed25519.verify(raw, message, signature))
        assertFalse(Ed25519.verify(raw, "mpmc!".toByteArray(), signature))
    }

    @Test
    fun `constant time digest comparison`() {
        assertTrue(ConstantTime.equalsDigest("secret-a".toByteArray(), "secret-a".toByteArray()))
        assertFalse(ConstantTime.equalsDigest("secret-a".toByteArray(), "secret-b".toByteArray()))
    }
}
