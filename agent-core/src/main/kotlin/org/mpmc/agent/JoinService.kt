package org.mpmc.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.mpmc.protocol.Frame
import org.mpmc.protocol.FrameCodec
import org.mpmc.protocol.FrameErrorCode
import org.mpmc.protocol.HelloErrPayload
import org.mpmc.protocol.HelloOkPayload
import org.mpmc.protocol.HelloPayload
import org.mpmc.protocol.JoinState
import org.mpmc.protocol.TicketCodec
import org.mpmc.transport.DuplexStream
import org.mpmc.transport.Peer
import org.mpmc.transport.PeerConnection
import org.mpmc.transport.PathKind
import org.mpmc.transport.PathSnapshot
import org.slf4j.LoggerFactory
import java.net.Socket
import java.time.Clock

class JoinService(
    private val config: AgentConfig,
    private val identity: AgentIdentity,
    private val peer: Peer,
    private val code: String,
    private val inviteToken: String?,
    private val status: SessionStore,
    private val coordinator: CoordinatorClient? = null,
    private val devMode: Boolean = false,
    private val devHostEndpointId: String? = null,
    private val devRoomId: String = DevTicket.ROOM_ID,
    private val relayUrlHint: String? = null,
    directAddresses: List<String> = emptyList(),
    private val clock: Clock = Clock.systemUTC(),
    private val version: String = "0.1.0",
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(JoinService::class.java)
    private val proxy = LocalProxy(config.join.listen)
    private val meter = Meter()
    private val ticketMutex = kotlinx.coroutines.sync.Mutex()
    private val activeLocal = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var directAddresses: List<String> = directAddresses

    fun updateDirectAddresses(addresses: List<String>) {
        directAddresses = addresses
    }

    @Volatile
    private var stopping = false

    @Volatile
    private var session: LiveSession? = null

    @Volatile
    private var ticket: String? = null

    private var joinRequestId: String? = null
    private var roomId: String = devRoomId
    private var hostEndpointId: String = devHostEndpointId ?: ""
    private var players: Int? = null
    private var setupMs: Long? = null

    private data class LiveSession(val conn: PeerConnection, val ticket: String)

    suspend fun run() = coroutineScope {
        try {
            prepare()
            proxy.bind()
            status.write(
                SessionState(
                    role = "join",
                    roomCode = code,
                    endpointId = identity.endpointId,
                    state = SessionState.STATE_CONNECTING,
                    localAddress = proxy.localAddress,
                ),
            )
            println("Sẵn sàng: ${proxy.localAddress}")
            val acceptJob = launch(Dispatchers.IO) {
                proxy.acceptLoop(this@coroutineScope) { socket -> handleLocalSocket(socket) }
            }
            val statusJob = launch(Dispatchers.Default) {
                while (isActive && !stopping) {
                    session?.let { live -> runCatching { publishConnected(live, setupMs) } }
                    delay(STATUS_INTERVAL_MS)
                }
            }
            connectLoop()
            statusJob.cancel()
            acceptJob.cancel()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: JoinFatalException) {
            fail(e.code, e.message ?: e.code.name)
        } catch (e: Exception) {
            log.warn("join_failed type=${e::class.simpleName} message=${e.message}")
            fail(FrameErrorCode.HOST_OFFLINE, e.message ?: "lỗi không xác định")
        } finally {
            session?.let { runCatching { it.conn.close() } }
            session = null
            proxy.close()
        }
    }

    override fun close() {
        stopping = true
        session?.let { runCatching { it.conn.close() } }
        proxy.close()
    }

    private suspend fun prepare() {
        if (devMode) {
            require(!devHostEndpointId.isNullOrBlank()) { "dev mode cần host endpoint id" }
            hostEndpointId = devHostEndpointId
            return
        }
        val client = coordinator ?: throw JoinFatalException(FrameErrorCode.HOST_OFFLINE, "thiếu Coordinator")
        client.authenticate()
        val join = client.joinRoom(code, inviteToken)
        joinRequestId = join.joinRequestId
        ticket = join.ticket ?: awaitTicket(client, join.joinRequestId)
        val info = try {
            client.roomInfo(code)
        } catch (e: CoordinatorException) {
            if (e.code == "ROOM_CLOSED") throw JoinFatalException(FrameErrorCode.ROOM_CLOSED, "phòng đã đóng")
            throw e
        }
        if (info.state != org.mpmc.protocol.RoomState.ACTIVE) {
            throw JoinFatalException(FrameErrorCode.ROOM_CLOSED, "phòng đã đóng")
        }
        roomId = info.roomId
        hostEndpointId = info.hostEndpointId
        players = info.memberCount
    }

    private suspend fun awaitTicket(client: CoordinatorClient, requestId: String): String =
        try {
            client.awaitApproval(requestId)
        } catch (e: CoordinatorException) {
            when (e.code) {
                "JOIN_REJECTED" -> throw JoinFatalException(FrameErrorCode.NOT_A_MEMBER, "host đã từ chối")
                "JOIN_EXPIRED" -> throw JoinFatalException(FrameErrorCode.TICKET_EXPIRED, "yêu cầu vào phòng đã hết hạn")
                else -> throw e
            }
        }

    private suspend fun connectLoop() = coroutineScope {
        val backoff = Backoff()
        var reconnecting = false

        suspend fun retryAfter(error: Exception): Boolean {
            session = null
            proxy.closeSockets()
            if (stopping) return false
            if (relayUnavailable()) {
                fail(FrameErrorCode.RELAY_UNAVAILABLE, "relay không phản hồi: $relayUrlHint")
                return false
            }
            reconnecting = true
            val delayMs = backoff.nextDelayMs()
            if (delayMs == null) {
                fail(FrameErrorCode.HOST_OFFLINE, "không kết nối lại được sau 5 lần")
                return false
            }
            log.info("connect_retry delay_ms=$delayMs reason=${error::class.simpleName}:${error.message}")
            publish(SessionState.STATE_RECONNECTING, error = error.message)
            delay(delayMs)
            return true
        }

        while (isActive && !stopping) {
            publish(if (reconnecting) SessionState.STATE_RECONNECTING else SessionState.STATE_CONNECTING)
            val startedAt = System.currentTimeMillis()
            try {
                val current = currentTicket()
                val conn = peer.dial(hostEndpointId, directAddresses, relayUrlHint)
                val control = withTimeout(config.transport.connectTimeoutSec * 1000) { conn.openStream() }
                sendHello(control, current)
                val response = withTimeout(20_000) { readFrame(control) }
                when (response) {
                    is Frame.HelloOk -> Unit
                    is Frame.HelloErr -> {
                        runCatching { control.close() }
                        runCatching { conn.close() }
                        if (response.payload.code == FrameErrorCode.TICKET_EXPIRED.name) {
                            ticket = null
                            throw RetryableJoinException("ticket hết hạn, thử lại")
                        }
                        throw JoinFatalException(
                            FrameErrorCode.entries.firstOrNull { it.name == response.payload.code }
                                ?: FrameErrorCode.PROTOCOL_UNSUPPORTED,
                            response.payload.message,
                        )
                    }
                    else -> throw RetryableJoinException("phản hồi handshake không hợp lệ")
                }
                runCatching { control.close() }

                backoff.reset()
                val live = LiveSession(conn, current)
                session = live
                reconnecting = false
                setupMs = System.currentTimeMillis() - startedAt
                publishConnected(live, setupMs)

                val pathJob = launch(Dispatchers.Default) {
                    runCatching { conn.pathEvents().collect { updatePath(live, it) } }
                }
                conn.closedReason()
                pathJob.cancel()
                session = null
                proxy.closeSockets()
                reconnecting = true
                if (!stopping) {
                    log.info("connection_closed; reconnecting")
                    val client = coordinator
                    if (client != null && coordinatorSaysClosed(client)) {
                        fail(FrameErrorCode.ROOM_CLOSED, "phòng đã đóng")
                        return@coroutineScope
                    }
                    publish(SessionState.STATE_RECONNECTING, error = "mất kết nối P2P")
                }
            } catch (e: JoinFatalException) {
                session = null
                fail(e.code, e.message ?: e.code.name)
                return@coroutineScope
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (!retryAfter(e)) return@coroutineScope
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!retryAfter(e)) return@coroutineScope
            }
        }
    }

    private fun relayUnavailable(): Boolean {
        if (directAddresses.isNotEmpty()) return false
        val hint = relayUrlHint ?: return false
        val uri = runCatching { java.net.URI(hint) }.getOrNull() ?: return false
        val host = uri.host?.removeSurrounding("[", "]") ?: return false
        val port = if (uri.port == -1) 443 else uri.port
        return runCatching {
            Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1_500) }
        }.isFailure
    }

    private suspend fun coordinatorSaysClosed(client: CoordinatorClient): Boolean =
        runCatching { client.roomInfo(code) }.fold(
            onSuccess = { it.state != org.mpmc.protocol.RoomState.ACTIVE },
            onFailure = { (it as? CoordinatorException)?.code == "ROOM_CLOSED" },
        )

    private suspend fun handleLocalSocket(socket: Socket) {
        if (activeLocal.incrementAndGet() > MAX_LOCAL_CONNECTIONS) {
            activeLocal.decrementAndGet()
            log.warn("local_connection_rejected open=$MAX_LOCAL_CONNECTIONS")
            resetSocket(socket)
            return
        }
        var stream: DuplexStream? = null
        try {
            repeat(2) { attempt ->
                val live = session ?: run {
                    resetSocket(socket)
                    return
                }
                val current = try {
                    currentTicket()
                } catch (e: JoinFatalException) {
                    log.warn("local_ticket_failed code=${e.code} message=${e.message}")
                    resetSocket(socket)
                    return
                }
                stream = try {
                    withTimeout(config.transport.connectTimeoutSec * 1000) { live.conn.openStream() }
                } catch (e: Exception) {
                    log.warn("local_open_stream_failed type=${e::class.simpleName} message=${e.message}")
                    resetSocket(socket)
                    return
                }
                sendHello(stream!!, current)
                val response = try {
                    withTimeout(config.transport.connectTimeoutSec * 1000) { readFrame(stream!!) }
                } catch (_: Exception) {
                    null
                }
                when (response) {
                    is Frame.HelloOk -> {
                        TunnelBridge.splice(socket, stream!!, meter)
                        return
                    }
                    is Frame.HelloErr -> {
                        log.warn("data_stream_rejected code=${response.payload.code}")
                        if (response.payload.code == FrameErrorCode.TICKET_EXPIRED.name && attempt == 0) {
                            stream?.let { runCatching { it.close() } }
                            stream = null
                            ticket = null
                            return@repeat
                        }
                        if (response.payload.code == FrameErrorCode.ROOM_CLOSED.name ||
                            response.payload.code == FrameErrorCode.HOST_SHUTDOWN.name
                        ) {
                            live.conn.close()
                        }
                        resetSocket(socket)
                        return
                    }
                    else -> {
                        log.warn("handshake_response_missing type=${response?.let { it::class.simpleName }}")
                        resetSocket(socket)
                        return
                    }
                }
            }
            resetSocket(socket)
        } finally {
            activeLocal.decrementAndGet()
            stream?.let { runCatching { it.close() } }
        }
    }

    private suspend fun currentTicket(): String = ticketMutex.withLock {
        val existing = ticket
        val payload = existing?.let { TicketCodec.decodeUnverified(it) }
        val now = clock.instant().epochSecond
        if (payload != null && payload.exp - now > 30) return@withLock existing
        if (devMode) {
            return@withLock DevTicket.issue(roomId, identity.endpointId, clock).also { ticket = it }
        }
        val client = coordinator ?: throw JoinFatalException(FrameErrorCode.HOST_OFFLINE, "thiếu Coordinator")
        val requestId = joinRequestId ?: throw JoinFatalException(FrameErrorCode.TICKET_INVALID, "chưa có join request")
        val refreshed = client.joinStatus(requestId)
        val fresh = refreshed.ticket
        when {
            refreshed.state == JoinState.REJECTED ->
                throw JoinFatalException(FrameErrorCode.NOT_A_MEMBER, "host đã từ chối")
            refreshed.state == JoinState.EXPIRED ->
                throw JoinFatalException(FrameErrorCode.TICKET_EXPIRED, "join request hết hạn")
            fresh == null -> throw JoinFatalException(FrameErrorCode.TICKET_INVALID, "không lấy được ticket")
        }
        ticket = fresh
        fresh
    }

    private suspend fun sendHello(stream: DuplexStream, ticketValue: String) {
        val hello = Frame.Hello(
            HelloPayload(
                roomId = roomId,
                endpointId = identity.endpointId,
                ticket = ticketValue,
                streamNonce = DevTicket.nonce(),
                clientVersion = version,
            ),
        )
        FrameCodec.write({ bytes -> stream.writeAll(bytes) }, hello)
    }

    private suspend fun readFrame(stream: DuplexStream): Frame? =
        FrameCodec.read { buf, off, len -> stream.read(buf, off, len) }

    private suspend fun updatePath(live: LiveSession, snapshot: PathSnapshot) {
        val paths = runCatching { live.conn.paths() }.getOrDefault(emptyList())
        publishConnected(live, setupMs = null, pathsOverride = paths)
    }

    private suspend fun publishConnected(
        live: LiveSession,
        setupMs: Long?,
        pathsOverride: List<PathSnapshot>? = null,
    ) {
        val paths = pathsOverride ?: runCatching { live.conn.paths() }.getOrDefault(emptyList())
        val kind = when {
            paths.isEmpty() -> PathKind.NONE
            paths.all { it.isRelay } -> PathKind.RELAY
            paths.none { it.isRelay } -> PathKind.DIRECT
            else -> PathKind.MIXED
        }
        val selected = paths.firstOrNull { it.isSelected } ?: paths.firstOrNull()
        val relay = paths.firstOrNull { it.isRelay }
        status.write(
            SessionState(
                role = "join",
                roomCode = code,
                endpointId = identity.endpointId,
                state = SessionState.STATE_CONNECTED,
                path = kind.name.lowercase(),
                rttMs = selected?.rttMs,
                relayRttMs = relay?.rttMs,
                setupMs = setupMs,
                localAddress = proxy.localAddress,
                players = players,
                bytesUp = meter.bytesUp,
                bytesDown = meter.bytesDown,
                openTcp = proxy.openConnections,
            ),
        )
    }

    private fun publish(state: String, error: String? = null) {
        status.write(
            SessionState(
                role = "join",
                roomCode = code,
                endpointId = identity.endpointId,
                state = state,
                localAddress = runCatching { proxy.localAddress }.getOrNull(),
                players = players,
                bytesUp = meter.bytesUp,
                bytesDown = meter.bytesDown,
                openTcp = proxy.openConnections,
                error = error,
            ),
        )
    }

    private fun fail(errorCode: FrameErrorCode, message: String) {
        stopping = true
        log.warn("join_error code=${errorCode.name} message=$message")
        status.write(
            SessionState(
                role = "join",
                roomCode = code,
                endpointId = identity.endpointId,
                state = SessionState.STATE_ERROR,
                localAddress = runCatching { proxy.localAddress }.getOrNull(),
                error = errorCode.name,
            ),
        )
    }

    private fun resetSocket(socket: Socket) {
        runCatching { socket.setSoLinger(true, 0) }
        runCatching { socket.close() }
    }

    private class RetryableJoinException(message: String) : Exception(message)

    private class JoinFatalException(val code: FrameErrorCode, message: String) : Exception(message)

    companion object {
        private const val MAX_LOCAL_CONNECTIONS = 64
        private const val STATUS_INTERVAL_MS = 2_000L
    }
}
