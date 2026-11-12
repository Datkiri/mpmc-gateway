package org.mpmc.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val FRAME_MAGIC = "MPMP"
const val FRAME_VERSION = 1
const val FRAME_HEADER_BYTES = 8
const val FRAME_MAX_PAYLOAD = 8192

enum class FrameType(val code: Int) {
    HELLO(1),
    HELLO_OK(2),
    HELLO_ERR(3),
    GOAWAY(4),
    ;

    companion object {
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
data class HelloPayload(
    val roomId: String,
    val endpointId: String,
    val ticket: String,
    val streamNonce: String,
    val clientVersion: String,
)

@Serializable
data class HelloOkPayload(
    val streamLimit: Int,
    val serverVersion: String,
)

@Serializable
data class HelloErrPayload(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

@Serializable
data class GoawayPayload(
    val code: String,
    val message: String,
)

sealed interface Frame {
    val type: FrameType

    data class Hello(val payload: HelloPayload) : Frame {
        override val type: FrameType get() = FrameType.HELLO
    }

    data class HelloOk(val payload: HelloOkPayload) : Frame {
        override val type: FrameType get() = FrameType.HELLO_OK
    }

    data class HelloErr(val payload: HelloErrPayload) : Frame {
        override val type: FrameType get() = FrameType.HELLO_ERR

        constructor(code: FrameErrorCode, message: String) :
            this(HelloErrPayload(code.name, message, code.retryable))
    }

    data class Goaway(val payload: GoawayPayload) : Frame {
        override val type: FrameType get() = FrameType.GOAWAY
    }
}

class FrameDecodeException(message: String) : Exception(message)

object FrameCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(frame: Frame): ByteArray {
        val payload = when (frame) {
            is Frame.Hello -> json.encodeToString(HelloPayload.serializer(), frame.payload)
            is Frame.HelloOk -> json.encodeToString(HelloOkPayload.serializer(), frame.payload)
            is Frame.HelloErr -> json.encodeToString(HelloErrPayload.serializer(), frame.payload)
            is Frame.Goaway -> json.encodeToString(GoawayPayload.serializer(), frame.payload)
        }.toByteArray(Charsets.UTF_8)
        if (payload.size > FRAME_MAX_PAYLOAD) {
            throw FrameDecodeException("payload too large: ${payload.size}")
        }
        val out = ByteArray(FRAME_HEADER_BYTES + payload.size)
        out[0] = 'M'.code.toByte()
        out[1] = 'P'.code.toByte()
        out[2] = 'M'.code.toByte()
        out[3] = 'P'.code.toByte()
        out[4] = FRAME_VERSION.toByte()
        out[5] = frame.type.code.toByte()
        out[6] = ((payload.size ushr 8) and 0xff).toByte()
        out[7] = (payload.size and 0xff).toByte()
        payload.copyInto(out, FRAME_HEADER_BYTES)
        return out
    }

    fun decode(bytes: ByteArray): Frame {
        if (bytes.size < FRAME_HEADER_BYTES) throw FrameDecodeException("frame shorter than header")
        val header = parseHeader(bytes)
        val body = bytes.copyOfRange(FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + header.length)
        return decodePayload(header.type, body)
    }

    data class Header(val type: FrameType, val length: Int)

    fun parseHeader(bytes: ByteArray): Header {
        if (bytes.size < FRAME_HEADER_BYTES) throw FrameDecodeException("frame shorter than header")
        if (bytes[0] != 'M'.code.toByte() || bytes[1] != 'P'.code.toByte() ||
            bytes[2] != 'M'.code.toByte() || bytes[3] != 'P'.code.toByte()
        ) {
            throw FrameDecodeException("bad magic")
        }
        if (bytes[4].toInt() and 0xff != FRAME_VERSION) throw FrameDecodeException("bad version")
        val type = FrameType.fromCode(bytes[5].toInt() and 0xff)
            ?: throw FrameDecodeException("unknown frame type")
        val length = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        if (length > FRAME_MAX_PAYLOAD) throw FrameDecodeException("payload too large: $length")
        return Header(type, length)
    }

    private fun decodePayload(type: FrameType, body: ByteArray): Frame {
        val text = body.toString(Charsets.UTF_8)
        return try {
            when (type) {
                FrameType.HELLO -> Frame.Hello(json.decodeFromString(HelloPayload.serializer(), text))
                FrameType.HELLO_OK -> Frame.HelloOk(json.decodeFromString(HelloOkPayload.serializer(), text))
                FrameType.HELLO_ERR -> Frame.HelloErr(json.decodeFromString(HelloErrPayload.serializer(), text))
                FrameType.GOAWAY -> Frame.Goaway(json.decodeFromString(GoawayPayload.serializer(), text))
            }
        } catch (e: Exception) {
            throw FrameDecodeException("bad payload: ${e.message}")
        }
    }

    /** Đọc đúng một frame; trả null nếu stream đóng sạch trước khi có byte nào. */
    suspend fun read(readSome: suspend (ByteArray, Int, Int) -> Int): Frame? {
        val header = ByteArray(FRAME_HEADER_BYTES)
        val headerBytes = readFully(readSome, header)
        if (headerBytes == 0) return null
        if (headerBytes < FRAME_HEADER_BYTES) throw FrameDecodeException("ended mid-header")
        val parsed = parseHeader(header)
        val body = ByteArray(parsed.length)
        if (body.isNotEmpty() && readFully(readSome, body) < body.size) {
            throw FrameDecodeException("ended mid-payload")
        }
        return decodePayload(parsed.type, body)
    }

    suspend fun write(writeAll: suspend (ByteArray) -> Unit, frame: Frame) {
        writeAll(encode(frame))
    }

    private suspend fun readFully(readSome: suspend (ByteArray, Int, Int) -> Int, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = readSome(buf, offset, buf.size - offset)
            if (n < 0) {
                if (offset == 0) return 0
                throw FrameDecodeException("stream ended mid-frame")
            }
            if (n == 0) continue
            offset += n
        }
        return offset
    }
}
