package org.mpmc.agent

import org.mpmc.protocol.B64Url
import org.mpmc.protocol.Ed25519
import org.mpmc.protocol.TicketCodec
import org.mpmc.protocol.TicketPayload
import org.mpmc.protocol.Tokens
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.UUID

/**
 * Ticket tự ký dùng cho dev/M2 khi chưa có Coordinator.
 * Host chỉ chấp nhận khi bật `--insecure-dev-ticket`; chữ ký KHÔNG được xác minh.
 */
object DevTicket {
    const val ROOM_ID = "00000000-0000-0000-0000-000000000001"
    const val CODE = "DEV2345"

    private val pair: KeyPair by lazy {
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }

    fun issue(
        roomId: String = ROOM_ID,
        endpointId: String,
        clock: Clock = Clock.systemUTC(),
        ttlSeconds: Long = 120,
        maxStreams: Int = 4,
    ): String {
        val now = clock.instant().epochSecond
        val payload = TicketPayload(
            roomId = roomId,
            code = CODE,
            sub = endpointId,
            jti = UUID.randomUUID().toString(),
            iat = now,
            exp = now + ttlSeconds,
            maxStreams = maxStreams,
        )
        return TicketCodec.encode(payload) { message -> Ed25519.sign(pair.private, message) }
    }

    fun nonce(): String = B64Url.encode(Tokens.randomBytes(16))
}
