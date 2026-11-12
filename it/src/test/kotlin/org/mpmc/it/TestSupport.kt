package org.mpmc.it

import org.mpmc.agent.AgentConfig
import org.mpmc.agent.CoordinatorSettings
import org.mpmc.agent.HostRoom
import org.mpmc.agent.HostService
import org.mpmc.agent.DevTicket
import org.mpmc.agent.JoinService
import org.mpmc.agent.JoinSettings
import org.mpmc.agent.HostSettings
import org.mpmc.agent.KeyStore
import org.mpmc.agent.SessionState
import org.mpmc.agent.SessionStore
import org.mpmc.agent.TransportSettings
import org.mpmc.transport.IrohTransport
import org.mpmc.transport.Peer
import org.mpmc.transport.RelayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

internal fun freePort(): Int = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { it.localPort }

internal fun newDataDir(prefix: String): Path = Files.createTempDirectory(prefix)

internal fun waitUntil(timeoutMs: Long = 30_000, intervalMs: Long = 50, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(intervalMs)
    }
    return condition()
}

internal fun waitForState(store: SessionStore, state: String, timeoutMs: Long = 30_000): SessionState? {
    waitUntil(timeoutMs) { store.read()?.state == state }
    return store.read()?.takeIf { it.state == state }
}

internal fun awaitLocalAddress(store: SessionStore, timeoutMs: Long = 5_000): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        store.read()?.localAddress?.let { return it }
        Thread.sleep(20)
    }
    return null
}

/** TCP echo server: phản chiếu dữ liệu; khi client gửi FIN, trả thêm "TAIL" rồi đóng. */
internal class EchoServer(
    private val halfCloseTail: Boolean = true,
) : AutoCloseable {
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    val address: String get() = "127.0.0.1:${server.localPort}"
    val port: Int get() = server.localPort

    private val worker = thread(name = "echo-accept", isDaemon = true) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(isDaemon = true) { echo(socket) }
        }
    }

    private fun echo(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
            if (halfCloseTail) {
                runCatching {
                    output.write("TAIL".toByteArray())
                    output.flush()
                }
            }
        }
    }

    fun acceptRaw(): Socket = server.accept()

    override fun close() {
        runCatching { server.close() }
    }
}

internal class TunnelHarness(
    private val relayMode: RelayMode = RelayMode.Disabled,
    private val hostBind: String = "127.0.0.1:0",
    private val avoidHalfCloseTail: Boolean = false,
    private val relayUrl: String? = null,
    private val passDirect: Boolean = true,
) : AutoCloseable {
    private val effectiveRelay = relayUrl?.let { RelayMode.Custom(listOf(it)) } ?: relayMode
    val echo = EchoServer(halfCloseTail = !avoidHalfCloseTail)
    val hostDir: Path = newDataDir("mpmc-host-")
    val joinDir: Path = newDataDir("mpmc-join-")
    val hostIdentity = KeyStore.loadOrCreate(hostDir)
    val joinIdentity = KeyStore.loadOrCreate(joinDir)
    val hostStatus = SessionStore(hostDir)
    val joinStatus = SessionStore(joinDir)

    private val transport = IrohTransport()
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

    lateinit var hostJob: kotlinx.coroutines.Job
        private set
    lateinit var joinJob: kotlinx.coroutines.Job
        private set
    lateinit var scope: kotlinx.coroutines.CoroutineScope
        private set

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

    suspend fun start(scope: kotlinx.coroutines.CoroutineScope) {
        this.scope = scope
        hostPeer = transport.bind(
            org.mpmc.transport.TransportConfig(
                secretKey = hostIdentity.seed,
                relayMode = effectiveRelay,
                bindAddr = hostBind,
            ),
        )
        joinPeer = transport.bind(
            org.mpmc.transport.TransportConfig(
                secretKey = joinIdentity.seed,
                relayMode = effectiveRelay,
                bindAddr = "127.0.0.1:0",
            ),
        )
        hostService = HostService(
            config = hostConfig,
            identity = hostIdentity,
            peer = hostPeer,
            room = HostRoom(DevTicket.ROOM_ID, DevTicket.CODE, "dev"),
            status = hostStatus,
            devMode = true,
        )
        hostJob = scope.launch(Dispatchers.Default) { hostService.run() }
        val direct = if (passDirect) hostPeer.boundAddresses() else emptyList()
        joinService = JoinService(
            config = joinConfig,
            identity = joinIdentity,
            peer = joinPeer,
            code = DevTicket.CODE,
            inviteToken = null,
            status = joinStatus,
            devMode = true,
            devHostEndpointId = hostPeer.endpointId,
            relayUrlHint = relayUrl,
            directAddresses = direct,
        )
        joinJob = scope.launch(Dispatchers.Default) { joinService.run() }
        check(waitUntil(30_000) { joinStatus.read()?.localAddress != null }) {
            "join proxy không bind; session=${joinStatus.read()}"
        }
        proxyAddress = awaitLocalAddress(joinStatus) ?: error("không đọc được localAddress; session=${joinStatus.read()}")
        check(waitForState(joinStatus, SessionState.STATE_CONNECTED, 30_000) != null) {
            "join không CONNECTED; session=${joinStatus.read()}"
        }
    }

    fun connect(): Socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", proxyAddress.substringAfterLast(':').toInt()), 5_000)
        soTimeout = 30_000
        tcpNoDelay = true
    }

    suspend fun restartHost() {
        val newPeer = IrohTransport().bind(
            org.mpmc.transport.TransportConfig(
                secretKey = hostIdentity.seed,
                relayMode = effectiveRelay,
                bindAddr = "127.0.0.1:0",
            ),
        )
        hostPeer = newPeer
        if (passDirect) joinService.updateDirectAddresses(newPeer.boundAddresses())
        hostService = HostService(
            config = hostConfig,
            identity = hostIdentity,
            peer = newPeer,
            room = HostRoom(DevTicket.ROOM_ID, DevTicket.CODE, "dev"),
            status = hostStatus,
            devMode = true,
        )
        hostJob = scope.launch(Dispatchers.Default) { hostService.run() }
    }

    override fun close() {
        runCatching { joinService.close() }
        runCatching { hostService.close() }
        runCatching { joinPeer.close() }
        runCatching { hostPeer.close() }
        runCatching { echo.close() }
        runCatching { joinJob.cancel() }
        runCatching { hostJob.cancel() }
    }
}

internal fun Socket.readFully(expected: Int): ByteArray {
    val out = ByteArray(expected)
    var offset = 0
    val input: InputStream = getInputStream()
    while (offset < expected) {
        val read = input.read(out, offset, expected - offset)
        if (read == -1) break
        offset += read
    }
    check(offset == expected) { "chỉ đọc được $offset/$expected byte" }
    return out
}

internal fun OutputStream.writeFully(bytes: ByteArray) {
    write(bytes)
    flush()
}
