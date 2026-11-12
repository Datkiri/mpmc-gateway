package org.mpmc.it

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mpmc.agent.AgentConfig
import org.mpmc.agent.CoordinatorSettings
import org.mpmc.agent.JoinService
import org.mpmc.agent.JoinSettings
import org.mpmc.agent.HostSettings
import org.mpmc.agent.KeyStore
import org.mpmc.agent.SessionState
import org.mpmc.agent.SessionStore
import org.mpmc.agent.TransportSettings
import org.mpmc.transport.IrohTransport
import org.mpmc.transport.RelayMode
import org.mpmc.transport.TransportConfig
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Tìm binary iroh-relay đi kèm `_probe/`. */
internal object RelayBinary {
    fun locate(): Path? = listOf(
        Paths.get("_probe", "iroh-relay.exe"),
        Paths.get("..", "_probe", "iroh-relay.exe"),
        Paths.get("_probe", "iroh-relay"),
        Paths.get("..", "_probe", "iroh-relay"),
    ).firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.normalize()
}

internal class RelayProcess(port: Int = freePort()) : AutoCloseable {
    val url = "http://127.0.0.1:$port"
    private val metricsPort = freePort()
    private val config: Path = Files.createTempFile("iroh-relay-it", ".toml")
    private val process: Process

    init {
        val binary = RelayBinary.locate() ?: error("không tìm thấy iroh-relay binary")
        Files.writeString(
            config,
            "http_bind_addr = \"127.0.0.1:$port\"\nmetrics_bind_addr = \"127.0.0.1:$metricsPort\"\n",
        )
        process = ProcessBuilder(binary.toString(), "--config-path", config.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        check(waitUntil(20_000) { tcpOpen(port) }) { "iroh-relay không lên cổng $port" }
    }

    private fun tcpOpen(port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess

    override fun close() {
        runCatching { process.destroyForcibly() }
        runCatching { Files.deleteIfExists(config) }
    }
}

class RelayIT {
    @Test
    fun `relay only tunnel works without direct addresses`() = runBlocking {
        assumeTrue(RelayBinary.locate() != null, "thiếu _probe/iroh-relay")
        val relay = RelayProcess()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            TunnelHarness(relayUrl = relay.url, passDirect = false).use { harness ->
                try {
                    harness.start(scope)
                    harness.connect().use { socket ->
                        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
                        socket.outputStream.write(payload)
                        socket.outputStream.flush()
                        val echoed = socket.readFully(payload.size)
                        assertTrue(payload.contentEquals(echoed), "echo qua relay sai dữ liệu")
                    }
                    val session = harness.joinStatus.read()
                    assertEquals(SessionState.STATE_CONNECTED, session?.state)
                    assertTrue(
                        session?.path in setOf("relay", "mixed"),
                        "kết nối relay-only phải báo path relay/mixed, nhận: ${session?.path}",
                    )
                } finally {
                    harness.close()
                }
            }
        } finally {
            scope.cancel()
            relay.close()
        }
    }

    @Test
    fun `unreachable relay maps to RELAY_UNAVAILABLE quickly`() = runBlocking {
        val deadPort = freePort()
        val url = "http://127.0.0.1:$deadPort"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = newDataDir("relay-dead-")
        val identity = KeyStore.loadOrCreate(dir)
        val status = SessionStore(dir)
        val transport = IrohTransport()
        val peer = transport.bind(
            TransportConfig(
                secretKey = identity.seed,
                relayMode = RelayMode.Custom(listOf(url)),
                bindAddr = "127.0.0.1:0",
            ),
        )
        val config = AgentConfig(
            dataDir = dir,
            coordinator = CoordinatorSettings("http://127.0.0.1:1", insecureDev = true),
            transport = TransportSettings(relayMode = "custom", relayUrls = listOf(url)),
            host = HostSettings(target = "127.0.0.1:1"),
            join = JoinSettings(listen = "127.0.0.1:0"),
        )
        val service = JoinService(
            config = config,
            identity = identity,
            peer = peer,
            code = "DEV2345",
            inviteToken = null,
            status = status,
            devMode = true,
            devHostEndpointId = "1".repeat(64),
            relayUrlHint = url,
            directAddresses = emptyList(),
        )
        val startedAt = System.currentTimeMillis()
        val job = scope.launch { service.run() }
        try {
            check(waitForState(status, SessionState.STATE_ERROR, 60_000) != null) {
                "join không chuyển ERROR; session=${status.read()}"
            }
            val elapsed = System.currentTimeMillis() - startedAt
            assertEquals("RELAY_UNAVAILABLE", status.read()?.error)
            assertTrue(elapsed < 60_000, "phải fail nhanh, thực tế ${elapsed}ms")
        } finally {
            runCatching { service.close() }
            runCatching { peer.close() }
            runCatching { job.cancel() }
            scope.cancel()
        }
    }
}
