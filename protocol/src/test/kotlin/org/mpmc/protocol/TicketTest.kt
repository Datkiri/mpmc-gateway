package org.mpmc.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.UUID

class TicketTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rawPublic: ByteArray = Ed25519.rawFromPublic(keys.public)
    private val roomId = UUID.randomUUID().toString()
    private val endpointId = Tokens.randomHex(32)
    private val now = 1_770_000_000L

    private fun payload(
        room: String = roomId,
        sub: String = endpointId,
        iat: Long = now,
        exp: Long = now + 120,
        version: Int = 1,
    ) = TicketPayload(
        v = version,
        roomId = room,
        code = "ABCD2345",
        sub = sub,
        jti = UUID.randomUUID().toString(),
        iat = iat,
        exp = exp,
    )

    private fun sign(p: TicketPayload, keyPair: KeyPair = keys): String =
        TicketCodec.encode(p) { Ed25519.sign(keyPair.private, it) }

    private fun verify(ticket: String, room: String? = roomId, sub: String? = endpointId): TicketResult =
        TicketCodec.verify(
            ticket = ticket,
            verifySignature = { msg, sig -> Ed25519.verify(rawPublic, msg, sig) },
            nowEpochSeconds = now,
            expectedRoomId = room,
            expectedEndpointId = sub,
        )

    @Test
    fun `valid ticket verifies with bound room and subject`() {
        val p = payload()
        val result = verify(sign(p))
        val valid = assertInstanceOf(TicketResult.Valid::class.java, result)
        assertEquals(p.jti, valid.payload.jti)
        assertEquals(4, valid.payload.maxStreams)
        assertEquals("mpmc-coordinator", valid.payload.iss)
        assertEquals(p, TicketCodec.decodeUnverified(sign(p)))
    }

    @Test
    fun `tampered signature is rejected`() {
        val ticket = sign(payload())
        val body = ticket.substringBefore('.')
        val flipped = B64Url.decode(ticket.substringAfter('.'))
        flipped[0] = (flipped[0].toInt() xor 0x01).toByte()
        val tampered = "$body.${B64Url.encode(flipped)}"
        assertEquals(
            TicketResult.Invalid(TicketFailure.BAD_SIGNATURE),
            verify(tampered),
        )
    }

    @Test
    fun `signature from another key is rejected`() {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val ticket = sign(payload(), other)
        assertEquals(TicketResult.Invalid(TicketFailure.BAD_SIGNATURE), verify(ticket))
    }

    @Test
    fun `expired ticket is rejected`() {
        val ticket = sign(payload(iat = now - 180, exp = now))
        assertEquals(TicketResult.Invalid(TicketFailure.EXPIRED), verify(ticket))
    }

    @Test
    fun `wrong subject is rejected`() {
        val ticket = sign(payload())
        assertEquals(
            TicketResult.Invalid(TicketFailure.WRONG_SUBJECT),
            verify(ticket, sub = Tokens.randomHex(32)),
        )
    }

    @Test
    fun `wrong room is rejected`() {
        val ticket = sign(payload())
        assertEquals(
            TicketResult.Invalid(TicketFailure.WRONG_ROOM),
            verify(ticket, room = UUID.randomUUID().toString()),
        )
    }

    @Test
    fun `bad version is rejected`() {
        val ticket = sign(payload(version = 2))
        assertEquals(TicketResult.Invalid(TicketFailure.BAD_VERSION), verify(ticket))
    }

    @Test
    fun `malformed tickets are rejected`() {
        assertEquals(TicketResult.Invalid(TicketFailure.MALFORMED), verify("garbage"))
        assertEquals(TicketResult.Invalid(TicketFailure.MALFORMED), verify("a.b.c"))
        assertEquals(TicketResult.Invalid(TicketFailure.MALFORMED), verify("!!!.???"))
        assertNull(TicketCodec.decodeUnverified("no-dot"))
    }
}
