package org.mpmc.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.mpmc.protocol.Frame
import org.mpmc.protocol.FrameCodec
import org.mpmc.protocol.FrameDecodeException
import org.mpmc.protocol.FrameErrorCode
import org.mpmc.protocol.HelloOkPayload
import org.mpmc.protocol.HelloPayload
import org.mpmc.protocol.TicketCodec
import org.mpmc.protocol.TicketFailure
import org.mpmc.protocol.TicketPayload
import org.mpmc.protocol.TicketResult
import org.mpmc.protocol.WsEvent
import org.mpmc.transport.DuplexStream
import org.mpmc.transport.Incoming
import org.mpmc.transport.Peer
import org.mpmc.transport.PeerConnection
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class HostRoom(
    val roomId: String,
    val code: String,
    val hostToken: String,
    val inviteToken: String? = null,
)

private data class ActiveConnection(
    val conn: PeerConnection,
    val endpointId: String,
    val ticket: TicketPayload,
    val control: DuplexStream,
    val jti: JtiTracker,
    val streams: MutableSet<DuplexStream> = ConcurrentHashMap.newKeySet(),
) {
    fun close() {
        streams.forEach { runCatching { it.close() } }
        runCatching { conn.close() }
    }
}

class HostService(
    private val config: AgentConfig,
    private val identity: AgentIdentity,
    private val peer: Peer,
    private val room: HostRoom,
    private val status: SessionStore,
    private val coordinator: CoordinatorClient? = null,
    private val verifier: JwksCache? = null,
    private val events: kotlinx.coroutines.flow.Flow<WsEvent>? = null,
    private val devMode: Boolean = false,
    private val approvalDecider: (suspend (WsEvent) -> Boolean)? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val version: String = "0.1.0",
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(HostService::class.java)
    private val connections = ConcurrentHashMap<String, ActiveConnection>()
    private val totalStreams = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val globalMeter = Meter()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val targetAddress = AgentConfig.parseHostPort(config.host.target)
    private val targetHost = targetAddress.first
    private val targetPort = targetAddress.second

    val endpointId: String get() = identity.endpointId

    suspend fun run() = coroutineScope {
        publish(SessionState.STATE_DISCOVERING)
        verifier?.preload()
        val heartbeat = coordinator?.let { client ->
            launch { heartbeatLoop(client) }
        }
        val eventJob = events?.let { flow ->
            launch { eventLoop(flow) }
        }
        val statusJob = launch { statusLoop() }
        try {
            peer.incoming().collect { incoming ->
                launch(Dispatchers.Default) { handleIncoming(incoming) }
            }
        } finally {
            close()
            heartbeat?.cancel()
            eventJob?.cancel()
            statusJob.cancel()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connections.values.forEach { active -> active.close() }
        connections.clear()
        runCatching { peer.close() }
    }

    private suspend fun gracefulClose(code: FrameErrorCode) {
        if (!closed.compareAndSet(false, true)) return
        connections.values.forEach { active ->
            runCatching {
                withTimeout(1_000) { sendFrame(active.control, Frame.Goaway(org.mpmc.protocol.GoawayPayload(code.name, "shutdown"))) }
            }
            active.close()
        }
        connections.clear()
        runCatching { peer.close() }
    }

    private suspend fun heartbeatLoop(client: CoordinatorClient) {
        while (!closed.get()) {
            runCatching { client.heartbeat(room.code, room.hostToken) }
                .onFailure { log.warn("heartbeat_failed type=${it::class.simpleName}") }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private suspend fun eventLoop(flow: kotlinx.coroutines.flow.Flow<WsEvent>) {
        runCatching {
            flow.collect { event ->
                when (event.type) {
                    "member.left" -> {
                        val kicked = event.data["endpointId"]?.jsonPrimitive?.contentOrNull
                        if (kicked != null) {
                            blockedUntil[kicked] = clock.millis() + MEMBER_BLOCK_MS
                            connections[kicked]?.let { active ->
                                log.info("member_kicked endpoint=${kicked.take(8)}…")
                                active.streams.forEach { runCatching { it.reset(0) } }
                                active.streams.clear()
                                connections.remove(kicked, active)
                                runCatching { active.conn.close() }
                            }
                        }
                    }
                    "room.closed", "host.offline" -> {
                        log.info("room_closed_by_coordinator")
                        gracefulClose(FrameErrorCode.ROOM_CLOSED)
                    }
                    "join.requested" -> handleJoinRequested(event)
                }
            }
        }.onFailure { log.warn("event_stream_ended type=${it::class.simpleName}") }
    }

    private suspend fun handleJoinRequested(event: WsEvent) {
        val joinRequestId = event.data["joinRequestId"]?.jsonPrimitive?.contentOrNull ?: return
        val client = coordinator ?: return
        val decider = approvalDecider ?: run {
            log.info("join_requested id=$joinRequestId")
            return
        }
        val approved = runCatching { decider(event) }.getOrDefault(false)
        runCatching {
            if (approved) {
                client.approve(joinRequestId, room.hostToken)
                log.info("join_request_approved id=$joinRequestId")
            } else {
                client.reject(joinRequestId, room.hostToken, "host từ chối")
                log.info("join_request_rejected id=$joinRequestId")
            }
        }.onFailure { log.warn("join_decision_failed id=$joinRequestId type=${it::class.simpleName}") }
    }

    private suspend fun statusLoop() {
        while (!closed.get()) {
            publish(if (connections.isEmpty()) SessionState.STATE_CONNECTED else SessionState.STATE_CONNECTED)
            delay(STATUS_INTERVAL_MS)
        }
    }

    private fun publish(state: String) {
        status.write(
            SessionState(
                role = "host",
                roomCode = room.code,
                endpointId = endpointId,
                state = state,
                path = null,
                bytesUp = globalMeter.bytesUp,
                bytesDown = globalMeter.bytesDown,
                openTcp = totalStreams.get(),
            ),
        )
    }

    private suspend fun CoroutineScope.handleIncoming(incoming: Incoming) {
        val conn = try {
            withTimeout(config.transport.handshakeTimeoutSec * 1000) { incoming.accept() }
        } catch (e: Exception) {
            runCatching { incoming.refuse() }
            return
        }
        var active: ActiveConnection? = null
        try {
            val control = withTimeout(config.transport.handshakeTimeoutSec * 1000) { conn.acceptStream() }
            val hello = readHello(control) ?: return
            val payload = validateHello(control, hello) ?: return
            val jti = JtiTracker(nowSeconds = { clock.instant().epochSecond })
            active = ActiveConnection(conn, hello.endpointId, payload, control, jti)
            connections.put(hello.endpointId, active)?.let { previous ->
                log.info("replacing_connection endpoint=${hello.endpointId.take(8)}…")
                previous.close()
            }
            log.info("host_connection endpoint=${hello.endpointId.take(8)}…")
            sendFrame(control, Frame.HelloOk(HelloOkPayload(streamLimitFor(payload), version)))

            while (!closed.get()) {
                val stream = try {
                    conn.acceptStream()
                } catch (e: Exception) {
                    break
                }
                launch(Dispatchers.Default) { handleDataStream(active, stream) }
            }
        } catch (e: FrameDecodeException) {
            log.debug("bad_frame reason=${e.message}")
        } catch (e: Exception) {
            if (!closed.get()) log.debug("connection_ended type=${e::class.simpleName}")
        } finally {
            val current = active
            if (current != null) {
                connections.remove(current.endpointId, current)
                current.close()
            } else {
                runCatching { conn.close() }
            }
        }
    }

    private suspend fun validateHello(stream: DuplexStream, hello: HelloPayload): TicketPayload? {
        if (!org.mpmc.protocol.EndpointId.isValid(hello.endpointId)) {
            fail(stream, FrameErrorCode.BAD_FRAME, "endpointId không hợp lệ")
            return null
        }
        blockedUntil[hello.endpointId]?.let { until ->
            if (until > clock.millis()) {
                fail(stream, FrameErrorCode.NOT_A_MEMBER, "endpoint đã bị kick")
                return null
            }
        }
        val now = clock.instant().epochSecond
        val result = if (devMode) {
            val payload = TicketCodec.decodeUnverified(hello.ticket)
            when {
                payload == null -> TicketResult.Invalid(TicketFailure.MALFORMED)
                payload.roomId != room.roomId -> TicketResult.Invalid(TicketFailure.WRONG_ROOM)
                payload.sub != hello.endpointId -> TicketResult.Invalid(TicketFailure.WRONG_SUBJECT)
                payload.exp <= now -> TicketResult.Invalid(TicketFailure.EXPIRED)
                else -> TicketResult.Valid(payload)
            }
        } else {
            val cache = verifier ?: run {
                fail(stream, FrameErrorCode.TICKET_INVALID, "thiếu JWKS")
                return null
            }
            cache.verify(
                ticket = hello.ticket,
                nowEpochSeconds = now,
                expectedRoomId = room.roomId,
                expectedEndpointId = hello.endpointId,
            )
        }
        return when (result) {
            is TicketResult.Valid -> result.payload
            is TicketResult.Invalid -> {
                log.debug("hello_rejected endpoint=${hello.endpointId.take(8)} reason=${result.reason.name}")
                fail(stream, result.reason.frameCode(), "ticket không hợp lệ: ${result.reason.name}")
                null
            }
        }
    }

    private suspend fun handleDataStream(active: ActiveConnection, stream: DuplexStream) {
        val startedAt = System.nanoTime()
        val meter = Meter()
        var counted = false
        var errorCode = "NONE"
        try {
            val hello = readHello(stream) ?: return
            val payload = validateHello(stream, hello) ?: return
            if (hello.endpointId != active.endpointId) {
                fail(stream, FrameErrorCode.NOT_A_MEMBER, "endpoint không khớp connection")
                return
            }
            if (!devMode && !active.jti.tryConsume(payload.jti, minOf(payload.maxStreams, config.host.maxStreamsPerTicket))) {
                fail(stream, FrameErrorCode.TOO_MANY_STREAMS, "vượt maxStreams của ticket")
                return
            }
            val total = totalStreams.incrementAndGet()
            if (total > config.host.maxConcurrentStreams) {
                totalStreams.decrementAndGet()
                fail(stream, FrameErrorCode.TOO_MANY_STREAMS, "vượt số stream đồng thời của phòng")
                return
            }
            counted = true
            active.streams.add(stream)

            val socket = try {
                connectTarget()
            } catch (e: Exception) {
                fail(stream, FrameErrorCode.TARGET_UNAVAILABLE, "không kết nối được server đích")
                return
            }
            sendFrame(stream, Frame.HelloOk(HelloOkPayload(streamLimitFor(payload), version)))
            TunnelBridge.splice(socket, stream, meter)
        } catch (e: FrameDecodeException) {
            errorCode = "BAD_FRAME"
            log.debug("data_bad_frame reason=${e.message}")
        } catch (_: Exception) {
            errorCode = "STREAM_ERROR"
        } finally {
            if (counted) totalStreams.decrementAndGet()
            active.streams.remove(stream)
            runCatching { stream.close() }
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            globalMeter.addUp(meter.bytesUp)
            globalMeter.addDown(meter.bytesDown)
            log.info(
                "stream_closed up=${meter.bytesUp} down=${meter.bytesDown} duration_ms=$durationMs error=$errorCode",
            )
        }
    }

    private suspend fun connectTarget(): Socket = withContext(Dispatchers.IO) {
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(targetHost, targetPort), TARGET_CONNECT_TIMEOUT_MS)
        }
    }

    private fun streamLimitFor(payload: TicketPayload): Int =
        if (devMode) {
            config.host.maxConcurrentStreams
        } else {
            minOf(payload.maxStreams, config.host.maxStreamsPerTicket, config.host.maxConcurrentStreams)
        }

    private suspend fun readHello(stream: DuplexStream): HelloPayload? {
        val frame = withTimeout(config.transport.handshakeTimeoutSec * 1000) {
            FrameCodec.read { buf, off, len -> stream.read(buf, off, len) }
        } ?: return null
        if (frame !is Frame.Hello) {
            fail(stream, FrameErrorCode.BAD_FRAME, "frame đầu tiên phải là HELLO")
            return null
        }
        return frame.payload
    }

    private suspend fun fail(stream: DuplexStream, code: FrameErrorCode, message: String) {
        runCatching { sendFrame(stream, Frame.HelloErr(code, message)) }
        runCatching { stream.finish() }
    }

    private suspend fun sendFrame(stream: DuplexStream, frame: Frame) {
        FrameCodec.write({ bytes -> stream.writeAll(bytes) }, frame)
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 20_000L
        private const val STATUS_INTERVAL_MS = 2_000L
        private const val TARGET_CONNECT_TIMEOUT_MS = 10_000
        private const val MEMBER_BLOCK_MS = 10 * 60_000L
    }
}
