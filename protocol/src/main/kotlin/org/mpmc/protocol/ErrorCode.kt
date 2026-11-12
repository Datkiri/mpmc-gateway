package org.mpmc.protocol

enum class FrameErrorCode(val retryable: Boolean) {
    BAD_FRAME(false),
    TICKET_INVALID(false),
    TICKET_EXPIRED(true),
    TICKET_REUSED(true),
    ROOM_CLOSED(false),
    NOT_A_MEMBER(false),
    TOO_MANY_STREAMS(true),
    TARGET_UNAVAILABLE(true),
    HOST_SHUTDOWN(false),
    HOST_OFFLINE(true),
    RELAY_UNAVAILABLE(true),
    PROTOCOL_UNSUPPORTED(false),
    RATE_LIMITED(true),
}
