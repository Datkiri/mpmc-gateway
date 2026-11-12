package org.mpmc.protocol

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

class FrameCodecTest {
    private val hello = Frame.Hello(
        HelloPayload(
            roomId = "6f9619ff-8b86-d011-b42d-00cf4fc964ff",
            endpointId = "ab".repeat(32),
            ticket = "body.sig",
            streamNonce = B64Url.encode(Tokens.randomBytes(16)),
            clientVersion = "0.1.0",
        ),
    )

    @Test
    fun `round trips all four frame types`() {
        val frames = listOf(
            hello,
            Frame.HelloOk(HelloOkPayload(streamLimit = 4, serverVersion = "0.1.0")),
            Frame.HelloErr(FrameErrorCode.TICKET_EXPIRED, "ticket expired"),
            Frame.Goaway(GoawayPayload("HOST_SHUTDOWN", "bye")),
        )
        for (frame in frames) {
            val decoded = FrameCodec.decode(FrameCodec.encode(frame))
            assertEquals(frame, decoded)
        }
    }

    @Test
    fun `rejects bad magic version and length`() {
        val encoded = FrameCodec.encode(hello)

        val badMagic = encoded.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows<FrameDecodeException> { FrameCodec.decode(badMagic) }

        val badVersion = encoded.copyOf().also { it[4] = 9.toByte() }
        assertThrows<FrameDecodeException> { FrameCodec.decode(badVersion) }

        val badType = encoded.copyOf().also { it[5] = 77.toByte() }
        assertThrows<FrameDecodeException> { FrameCodec.decode(badType) }

        val tooLong = encoded.copyOf().also {
            it[6] = 0x7f.toByte()
            it[7] = 0xff.toByte()
        }
        assertThrows<FrameDecodeException> { FrameCodec.decode(tooLong) }
    }

    @Test
    fun `rejects payload over 8192 bytes`() {
        val huge = Frame.Goaway(GoawayPayload("HOST_SHUTDOWN", "x".repeat(9000)))
        assertThrows<FrameDecodeException> { FrameCodec.encode(huge) }
    }

    @Test
    fun `stream read returns null on clean close and errors on partial header`() = runTest {
        val empty = ByteArrayInputStream(ByteArray(0))
        assertNull(FrameCodec.read { buf, off, len -> empty.read(buf, off, len) })

        val partial = ByteArrayInputStream(ByteArray(3) { 0x4d })
        assertThrows<FrameDecodeException> {
            FrameCodec.read { buf, off, len -> partial.read(buf, off, len) }
        }
    }

    @Test
    fun `stream read consumes frames back to back`() = runTest {
        val bytes = FrameCodec.encode(hello) +
            FrameCodec.encode(Frame.HelloOk(HelloOkPayload(4, "0.1.0")))
        val input = ByteArrayInputStream(bytes)
        val readSome: suspend (ByteArray, Int, Int) -> Int = { buf, off, len -> input.read(buf, off, len) }

        assertEquals(hello, FrameCodec.read(readSome))
        assertInstanceOf(Frame.HelloOk::class.java, FrameCodec.read(readSome))
        assertNull(FrameCodec.read(readSome))
    }

    @Test
    fun `header layout matches spec`() {
        val encoded = FrameCodec.encode(hello)
        assertArrayEquals(byteArrayOf(0x4d, 0x50, 0x4d, 0x50), encoded.copyOfRange(0, 4))
        assertEquals(1, encoded[4].toInt())
        assertEquals(1, encoded[5].toInt())
        val length = ((encoded[6].toInt() and 0xff) shl 8) or (encoded[7].toInt() and 0xff)
        assertEquals(encoded.size - 8, length)
    }
}
