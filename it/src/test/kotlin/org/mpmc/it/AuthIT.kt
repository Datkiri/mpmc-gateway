package org.mpmc.it

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mpmc.agent.HostService
import org.mpmc.agent.JwksCache
import org.mpmc.agent.SessionState
import org.mpmc.protocol.ApprovalMode
import org.mpmc.protocol.JoinState
import org.mpmc.protocol.TicketResult
import org.slf4j.LoggerFactory
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class AuthIT {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun Socket.echo(payload: String): String {
        outputStream.write(payload.toByteArray())
        outputStream.flush()
        return String(readFully(payload.toByteArray().size))
    }

    @Test
    fun `join request rejected without invite maps to NOT_A_MEMBER`() = runBlocking {
        val scope = scope()
        AuthHarness(approvalMode = ApprovalMode.PROMPT, approveJoin = false).use { harness ->
            try {
                harness.bootCoordinator()
                harness.createRoom()
                harness.startHost(scope)
                harness.startJoin(scope, requireProxy = false)
                val deadline = System.currentTimeMillis() + 40_000
                var session: SessionState? = null
                while (System.currentTimeMillis() < deadline) {
                    session = harness.joinStatus.read()
                    if (session?.state == SessionState.STATE_ERROR) break
                    Thread.sleep(100)
                }
                assertEquals(SessionState.STATE_ERROR, session?.state, "join phải dừng ở trạng thái lỗi")
                assertEquals("NOT_A_MEMBER", session?.error)
            } finally {
                harness.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `wrong invite stays pending and correct invite issues offline verifiable ticket`() = runBlocking {
        val scope = scope()
        AuthHarness(approvalMode = ApprovalMode.INVITE).use { harness ->
            try {
                harness.bootCoordinator()
                harness.createRoom()

                val pending = harness.joinClient.joinRoom(harness.room.code, "sai-invite-token")
                assertEquals(JoinState.PENDING, pending.state)
                val status = harness.joinClient.joinStatus(pending.joinRequestId)
                assertEquals(JoinState.PENDING, status.state)

                val approved = harness.joinClient.joinRoom(harness.room.code, harness.room.inviteToken)
                assertEquals(JoinState.APPROVED, approved.state)
                val ticket = approved.ticket
                assertNotNull(ticket, "invite đúng phải nhận ticket")
                requireNotNull(ticket)

                val verifier = JwksCache(harness.joinClient::jwksRawKeys, cacheFile = null)
                verifier.preload()
                val result = verifier.verify(
                    ticket = ticket,
                    nowEpochSeconds = java.time.Instant.now().epochSecond,
                    expectedRoomId = harness.room.roomId,
                    expectedEndpointId = harness.joinIdentity.endpointId,
                )
                val valid = result as? TicketResult.Valid
                assertNotNull(valid, "ticket phải verify offline bằng JWKS: $result")
                assertEquals(harness.room.roomId, valid!!.payload.roomId)
                assertEquals(harness.joinIdentity.endpointId, valid.payload.sub)
                assertTrue(valid.payload.exp > valid.payload.iat)
            } finally {
                harness.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `expired ticket is rejected then refreshed through join status`() = runBlocking {
        val scope = scope()
        val clock = MutableClock()
        val logger = LoggerFactory.getLogger(HostService::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            AuthHarness(approvalMode = ApprovalMode.INVITE, clock = clock).use { harness ->
                try {
                    harness.bootCoordinator()
                    harness.createRoom()
                    harness.startHost(scope)
                    harness.startJoin(scope)
                    harness.awaitJoinConnected()

                    harness.connect().use { socket ->
                        assertEquals("round-1", socket.echo("round-1"))
                    }

                    clock.advance(Duration.ofSeconds(300))

                    harness.connect().use { socket ->
                        assertEquals("round-2", socket.echo("round-2"))
                    }
                    val rejected = appender.list.any { it.formattedMessage.contains("reason=EXPIRED") }
                    assertTrue(rejected, "host phải từ chối vé cũ bằng TICKET_EXPIRED trước khi join refresh")
                    assertEquals(SessionState.STATE_CONNECTED, harness.joinStatus.read()?.state)
                } finally {
                    harness.close()
                    scope.cancel()
                }
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun `fifth concurrent stream on one ticket is rejected`() = runBlocking {
        val scope = scope()
        AuthHarness(approvalMode = ApprovalMode.INVITE).use { harness ->
            try {
                harness.bootCoordinator()
                harness.createRoom()
                harness.startHost(scope)
                harness.startJoin(scope)
                harness.awaitJoinConnected()

                val kept = CopyOnWriteArrayList<Socket>()
                try {
                    repeat(4) { index ->
                        val socket = harness.connect()
                        kept.add(socket)
                        assertEquals("s$index", socket.echo("s$index"))
                    }
                    val fifth = harness.connect()
                    val outcome = runCatching {
                        fifth.outputStream.write("s4".toByteArray())
                        fifth.outputStream.flush()
                        fifth.readFully(2)
                    }.exceptionOrNull()
                    val eof = outcome == null && runCatching { fifth.getInputStream().read() }.getOrNull() == -1
                    assertTrue(outcome != null || eof, "stream thứ 5 phải bị đóng/reset, outcome=$outcome")
                    runCatching { fifth.close() }

                    assertEquals("still", kept[0].echo("still"))
                } finally {
                    kept.forEach { runCatching { it.close() } }
                }
            } finally {
                harness.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `kick blocks endpoint and closes existing tunnel`() = runBlocking {
        val scope = scope()
        AuthHarness(approvalMode = ApprovalMode.INVITE).use { harness ->
            try {
                harness.bootCoordinator()
                harness.createRoom()
                harness.startHost(scope)
                harness.startJoin(scope)
                harness.awaitJoinConnected()
                harness.connect().use { socket -> assertEquals("pre", socket.echo("pre")) }

                Thread.sleep(1_500)
                harness.hostClient.kick(harness.room.code, harness.joinIdentity.endpointId, harness.room.hostToken)

                val deadline = System.currentTimeMillis() + 40_000
                var session: SessionState? = null
                while (System.currentTimeMillis() < deadline) {
                    session = harness.joinStatus.read()
                    if (session?.state == SessionState.STATE_ERROR) break
                    Thread.sleep(100)
                }
                assertEquals(SessionState.STATE_ERROR, session?.state, "join phải lỗi sau khi bị kick")
                assertEquals("NOT_A_MEMBER", session?.error)

                val blocked = runCatching {
                    harness.connect().use { socket ->
                        socket.outputStream.write("x".toByteArray())
                        socket.outputStream.flush()
                        socket.getInputStream().read()
                    }
                }
                assertTrue(blocked.isFailure || blocked.getOrNull() == -1, "kết nối mới sau kick phải bị đóng")
            } finally {
                harness.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `logs never contain room secrets`() = runBlocking {
        val scope = scope()
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            AuthHarness(approvalMode = ApprovalMode.INVITE).use { harness ->
                try {
                    harness.bootCoordinator()
                    harness.createRoom()
                    harness.startHost(scope)
                    harness.startJoin(scope)
                    harness.awaitJoinConnected()
                    harness.connect().use { socket -> assertEquals("no-secret", socket.echo("no-secret")) }
                    Thread.sleep(300)

                    val logged = appender.list.joinToString("\n") { it.formattedMessage }
                    assertFalse(logged.contains(harness.room.hostToken), "log chứa hostToken")
                    assertFalse(logged.contains(harness.room.inviteToken), "log chứa inviteToken")
                    assertFalse(logged.contains("Bearer "), "log chứa Authorization")
                } finally {
                    harness.close()
                    scope.cancel()
                }
            }
        } finally {
            root.detachAppender(appender)
        }
    }

    @Test
    fun `room close surfaces ROOM_CLOSED to joiner`() = runBlocking {
        val scope = scope()
        AuthHarness(approvalMode = ApprovalMode.INVITE).use { harness ->
            try {
                harness.bootCoordinator()
                harness.createRoom()
                harness.startHost(scope)
                harness.startJoin(scope)
                harness.awaitJoinConnected()
                harness.connect().use { socket -> assertEquals("pre", socket.echo("pre")) }

                Thread.sleep(1_500)
                harness.hostClient.closeRoom(harness.room.code, harness.room.hostToken)

                val deadline = System.currentTimeMillis() + 40_000
                var session: SessionState? = null
                while (System.currentTimeMillis() < deadline) {
                    session = harness.joinStatus.read()
                    if (session?.state == SessionState.STATE_ERROR) break
                    Thread.sleep(100)
                }
                assertEquals(SessionState.STATE_ERROR, session?.state, "join phải lỗi khi phòng đóng")
                assertEquals("ROOM_CLOSED", session?.error)
            } finally {
                harness.close()
                scope.cancel()
            }
        }
    }
}
