package org.mpmc.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TicketPayload(
    val v: Int = 1,
    val iss: String = TicketCodec.ISSUER,
    val roomId: String,
    val code: String,
    val sub: String,
    val role: String = "guest",
    val jti: String,
    val iat: Long,
    val exp: Long,
    val maxStreams: Int = 4,
    val relay: Boolean = true,
)

enum class TicketFailure {
    MALFORMED,
    BAD_SIGNATURE,
    BAD_VERSION,
    BAD_ISSUER,
    EXPIRED,
    WRONG_SUBJECT,
    WRONG_ROOM,
    ;

    fun frameCode(): FrameErrorCode = when (this) {
        EXPIRED -> FrameErrorCode.TICKET_EXPIRED
        else -> FrameErrorCode.TICKET_INVALID
    }
}

sealed interface TicketResult {
    data class Valid(val payload: TicketPayload) : TicketResult
    data class Invalid(val reason: TicketFailure) : TicketResult
}

object TicketCodec {
    const val ISSUER = "mpmc-coordinator"
    const val VERSION = 1
    private const val MAX_STREAMS_LIMIT = 16

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(payload: TicketPayload, sign: (ByteArray) -> ByteArray): String {
        val body = B64Url.encodeUtf8(json.encodeToString(TicketPayload.serializer(), payload))
        val signature = sign(body.toByteArray(Charsets.US_ASCII))
        return "$body.${B64Url.encode(signature)}"
    }

    fun decodeUnverified(ticket: String): TicketPayload? {
        val parts = ticket.split('.')
        if (parts.size != 2) return null
        return runCatching {
            json.decodeFromString(TicketPayload.serializer(), B64Url.decodeUtf8(parts[0]))
        }.getOrNull()
    }

    fun verify(
        ticket: String,
        verifySignature: (message: ByteArray, signature: ByteArray) -> Boolean,
        nowEpochSeconds: Long,
        expectedRoomId: String? = null,
        expectedEndpointId: String? = null,
    ): TicketResult {
        val parts = ticket.split('.')
        if (parts.size != 2) return TicketResult.Invalid(TicketFailure.MALFORMED)

        val payloadBytes = runCatching { B64Url.decode(parts[0]) }.getOrNull()
            ?: return TicketResult.Invalid(TicketFailure.MALFORMED)
        val signature = runCatching { B64Url.decode(parts[1]) }.getOrNull()
            ?: return TicketResult.Invalid(TicketFailure.MALFORMED)

        if (!verifySignature(parts[0].toByteArray(Charsets.US_ASCII), signature)) {
            return TicketResult.Invalid(TicketFailure.BAD_SIGNATURE)
        }

        val payload = runCatching {
            json.decodeFromString(TicketPayload.serializer(), payloadBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return TicketResult.Invalid(TicketFailure.MALFORMED)

        if (payload.v != VERSION) return TicketResult.Invalid(TicketFailure.BAD_VERSION)
        if (payload.iss != ISSUER) return TicketResult.Invalid(TicketFailure.BAD_ISSUER)
        if (payload.exp <= nowEpochSeconds) return TicketResult.Invalid(TicketFailure.EXPIRED)
        if (payload.exp <= payload.iat) return TicketResult.Invalid(TicketFailure.MALFORMED)
        if (payload.maxStreams !in 1..MAX_STREAMS_LIMIT) return TicketResult.Invalid(TicketFailure.MALFORMED)
        if (payload.role != "guest") return TicketResult.Invalid(TicketFailure.MALFORMED)
        if (expectedEndpointId != null && payload.sub != expectedEndpointId) {
            return TicketResult.Invalid(TicketFailure.WRONG_SUBJECT)
        }
        if (expectedRoomId != null && payload.roomId != expectedRoomId) {
            return TicketResult.Invalid(TicketFailure.WRONG_ROOM)
        }
        return TicketResult.Valid(payload)
    }
}
