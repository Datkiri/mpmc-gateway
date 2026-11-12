package org.mpmc.it

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mpmc.agent.AgentConfig
import org.mpmc.agent.AgentIdentity
import org.mpmc.agent.CoordinatorClient
import org.mpmc.agent.CoordinatorSettings
import org.mpmc.agent.HostRoom
import org.mpmc.agent.HostService
import org.mpmc.agent.HostSettings
import org.mpmc.agent.JoinService
import org.mpmc.agent.JoinSettings
import org.mpmc.agent.JwksCache
import org.mpmc.agent.KeyStore
import org.mpmc.agent.SessionState
import org.mpmc.agent.SessionStore
import org.mpmc.agent.TransportSettings
import org.mpmc.coordinator.auth.SigningKeys
import org.mpmc.coordinator.config.CoordinatorConfig
import org.mpmc.coordinator.module
import org.mpmc.coordinator.store.Database
import org.mpmc.protocol.ApprovalMode
import org.mpmc.protocol.CreateRoomRequest
import org.mpmc.protocol.CreateRoomResponse
import org.mpmc.transport.IrohTransport
import org.mpmc.transport.Peer
import org.mpmc.transport.RelayMode
import org.mpmc.transport.TransportConfig
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal class MutableClock(
    @Volatile private var current: Instant = Instant.now(),
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

/** PostgreSQL nhúng dùng chung cho toàn bộ AuthIT; schema migrate một lần. */
internal object AuthPg {
    private val log = LoggerFactory.getLogger(AuthPg::class.java)
    private val embedded: EmbeddedPostgres by lazy { EmbeddedPostgres.builder().start() }
    val database: Database by lazy {
        Database.connect(embedded.getJdbcUrl("postgres", "postgres"), "postgres", "postgres").also {
            it.migrate()
            log.warn("auth_it_using_embedded_postgres jdbcUrl=${embedded.getJdbcUrl("postgres", "postgres")}")
        }
    }
    val jdbcUrl: String get() = embedded.getJdbcUrl("postgres", "postgres")
}

/**
 * Coordinator thật (Netty + PostgreSQL nhúng) + host/join agent với xác thực thật.
 * Không dùng devMode: vé do coordinator ký, host verify offline qua JWKS.
 */
internal class AuthHarness(
    private val approvalMode: String = ApprovalMode.INVITE,
    private val approveJoin: Boolean = true,
    val clock: MutableClock = MutableClock(),
) : AutoCloseable {
    val echo = EchoServer()
    val hostDir: Path = newDataDir("auth-host-")
    val joinDir: Path = newDataDir("auth-join-")
    val hostIdentity: AgentIdentity = KeyStore.loadOrCreate(hostDir)
    val joinIdentity: AgentIdentity = KeyStore.loadOrCreate(joinDir)
    val hostStatus = SessionStore(hostDir)
    val joinStatus = SessionStore(joinDir)

    private val transport = IrohTransport()
    private lateinit var server: EmbeddedServer<*, *>
    lateinit var baseUrl: String
        private set
    lateinit var hostClient: CoordinatorClient
        private set
    lateinit var joinClient: CoordinatorClient
        private set
    lateinit var room: CreateRoomResponse
        private set
    lateinit var hostPeer: Peer
        private set
    lateinit var joinPeer: Peer
        private set
    lateinit var hostService: HostService
        private set
    lateinit var joinService: JoinService
        private set
    var proxyAddress: String = ""
        private set

    private var hostJob: kotlinx.coroutines.Job? = null
    private var joinJob: kotlinx.coroutines.Job? = null

    val hostConfig = AgentConfig(
        dataDir = hostDir,
        coordinator = CoordinatorSettings("http://127.0.0.1:1", insecureDev = true),
        transport = TransportSettings(relayMode = "disabled"),
        host = HostSettings(target = echo.address, maxConcurrentStreams = 40),
        join = JoinSettings(),
    )

    val joinConfig = AgentConfig(
        dataDir = joinDir,
        coordinator = CoordinatorSettings("http://127.0.0.1:1", insecureDev = true),
        transport = TransportSettings(relayMode = "disabled"),
        host = HostSettings(target = echo.address, maxConcurrentStreams = 40),
        join = JoinSettings(listen = "127.0.0.1:0"),
    )

    suspend fun bootCoordinator() {
        val port = freePort()
        baseUrl = "http://127.0.0.1:$port"
        val config = CoordinatorConfig(
            host = "127.0.0.1",
            port = port,
            databaseUrl = AuthPg.jdbcUrl,
            databaseUser = "postgres",
            databasePassword = "postgres",
            signingKeyPath = newDataDir("auth-signing-").resolve("signing.key"),
            relayCallbackToken = "auth-it-relay-token",
            ticketTtlSeconds = 120,
            joinRequestTtlSeconds = 3_600,
            roomHeartbeatTimeoutSeconds = 3_600,
            sweeperIntervalSeconds = 600,
        )
        val keys = SigningKeys.loadOrCreate(config.signingKeyPath)
        server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(config, AuthPg.database, keys, clock)
        }.start(wait = false)
        check(waitUntil(30_000) { healthz() }) { "coordinator không lên; url=$baseUrl" }
        hostClient = CoordinatorClient(baseUrl, hostIdentity)
        joinClient = CoordinatorClient(baseUrl, joinIdentity)
    }

    suspend fun createRoom() {
        room = hostClient.createRoom(
            CreateRoomRequest(maxMembers = 8, approvalMode = approvalMode, ttlMinutes = 120),
        )
    }

    suspend fun startHost(scope: CoroutineScope) {
        hostPeer = transport.bind(
            TransportConfig(secretKey = hostIdentity.seed, relayMode = RelayMode.Disabled, bindAddr = "127.0.0.1:0"),
        )
        val verifier = JwksCache(hostClient::jwksRawKeys, cacheFile = null, clock = clock)
        hostService = HostService(
            config = hostConfig,
            identity = hostIdentity,
            peer = hostPeer,
            room = HostRoom(room.roomId, room.code, room.hostToken, room.inviteToken),
            status = hostStatus,
            coordinator = hostClient,
            verifier = verifier,
            events = hostClient.events(),
            devMode = false,
            approvalDecider = { approveJoin },
            clock = clock,
        )
        hostJob = scope.launch(Dispatchers.Default) { hostService.run() }
    }

    suspend fun startJoin(scope: CoroutineScope, requireProxy: Boolean = true) {
        joinPeer = transport.bind(
            TransportConfig(secretKey = joinIdentity.seed, relayMode = RelayMode.Disabled, bindAddr = "127.0.0.1:0"),
        )
        joinService = JoinService(
            config = joinConfig,
            identity = joinIdentity,
            peer = joinPeer,
            code = room.code,
            inviteToken = room.inviteToken,
            status = joinStatus,
            coordinator = joinClient,
            devMode = false,
            directAddresses = hostPeer.boundAddresses(),
        )
        joinJob = scope.launch(Dispatchers.Default) { joinService.run() }
        if (requireProxy) {
            proxyAddress = awaitLocalAddress(joinStatus) ?: error("join proxy không bind; session=${joinStatus.read()}")
        }
    }

    suspend fun awaitJoinConnected(timeoutMs: Long = 40_000): SessionState =
        waitForState(joinStatus, SessionState.STATE_CONNECTED, timeoutMs)
            ?: error("join không CONNECTED; session=${joinStatus.read()}")

    fun connect(): java.net.Socket = java.net.Socket().apply {
        connect(java.net.InetSocketAddress("127.0.0.1", proxyAddress.substringAfterLast(':').toInt()), 5_000)
        soTimeout = 20_000
        tcpNoDelay = true
    }

    private fun healthz(): Boolean = runCatching {
        val conn = java.net.URI("$baseUrl/healthz").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 500
        conn.readTimeout = 500
        conn.requestMethod = "GET"
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    }.getOrDefault(false)

    override fun close() {
        runCatching { if (::joinService.isInitialized) joinService.close() }
        runCatching { if (::hostService.isInitialized) hostService.close() }
        runCatching { if (::joinPeer.isInitialized) joinPeer.close() }
        runCatching { if (::hostPeer.isInitialized) hostPeer.close() }
        runCatching { echo.close() }
        runCatching { joinJob?.cancel() }
        runCatching { hostJob?.cancel() }
        runCatching { if (::hostClient.isInitialized) hostClient.close() }
        runCatching { if (::joinClient.isInitialized) joinClient.close() }
        runCatching { if (::server.isInitialized) server.stop(0, 500) }
    }
}
