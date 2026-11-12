package org.mpmc.cli

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mpmc.agent.AgentConfig
import org.mpmc.agent.KeyStore
import org.mpmc.agent.gateway.BedrockGateway
import org.mpmc.transport.IrohTransport
import org.mpmc.transport.RelayMode
import org.mpmc.transport.TransportConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Callable

@Command(name = "doctor", mixinStandardHelpOptions = true, description = ["Kiểm tra môi trường trước khi chạy."])
class DoctorCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: MpmcCommand

    @Option(names = ["--bedrock"], description = ["Kiểm tra thêm server Bedrock đích host:port."])
    var bedrock: String? = null

    private var failures = 0

    override fun call(): Int {
        val feature = Runtime.version().feature()
        if (feature >= 21) pass("java", "$feature (${System.getProperty("java.version")})")
        else fail("java", "cần JDK 21+, hiện tại $feature")

        pass("os", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}")

        val dataDir = root.dataDir
        runCatching {
            Files.createDirectories(dataDir)
            val probe = dataDir.resolve(".doctor-probe")
            Files.writeString(probe, "ok")
            Files.deleteIfExists(probe)
        }.onSuccess { pass("data-dir", dataDir.toString()) }
            .onFailure { fail("data-dir", "không ghi được $dataDir: ${it.message}") }

        val config = runCatching { AgentConfig.loadOrDefault(dataDir).also { it.validate() } }
        if (config.isSuccess) {
            pass("config", config.getOrThrow().coordinator.url)
        } else {
            fail("config", config.exceptionOrNull()?.message ?: "không đọc được config")
        }

        val jna = runCatching { Class.forName("com.sun.jna.Native") }
        if (jna.isSuccess) pass("jna", "có sẵn") else fail("jna", "thiếu JNA runtime")

        val gateway = BedrockGateway(dataDir)
        when {
            gateway.isInstalled() -> pass("gateway", "ViaProxy ${gateway.buildLabel()}, ${gateway.verify()}")
            Files.isRegularFile(gateway.jar) -> fail("gateway", "$gateway.jar: ${gateway.verify()}")
            else -> pass("gateway", "chưa cài (chạy 'mpmc gateway install' khi host Bedrock)")
        }
        if (Files.isDirectory(gateway.directory) || gateway.isInstalled()) {
            pass("gateway-java", BedrockGateway.javaExecutable())
        }
        bedrock?.let { target ->
            bedrockCheck(target)
        }

        runCatching {
            val identity = KeyStore.loadOrCreate(dataDir)
            runBlocking {
                withTimeout(30_000) {
                    val transport = IrohTransport()
                    val peer = transport.bind(
                        TransportConfig(
                            secretKey = identity.seed,
                            relayMode = RelayMode.Disabled,
                            bindAddr = "127.0.0.1:0",
                        ),
                    )
                    try {
                        peer.endpointId to peer.boundAddresses()
                    } finally {
                        peer.close()
                    }
                }
            }
        }.onSuccess { (id, addrs) ->
            pass("iroh-bind", "endpoint=${id.take(16)}… addrs=${addrs.joinToString(",").ifEmpty { "?" }}")
        }.onFailure { fail("iroh-bind", it.message ?: it::class.simpleName ?: "lỗi") }

        config.getOrNull()?.let { cfg ->
            val (host, port) = runCatching { AgentConfig.parseHostPort(cfg.host.target) }.getOrNull() ?: ("" to 0)
            if (tcpReachable(host, port, 3_000)) pass("target", cfg.host.target)
            else warn("target", "không kết nối được ${cfg.host.target} (chỉ cần khi chạy 'host')")

            healthz(cfg).let { (state, detail) ->
                when (state) {
                    Check.PASS -> pass("coordinator", detail)
                    Check.WARN -> warn("coordinator", detail)
                    Check.FAIL -> fail("coordinator", detail)
                }
            }

            dnsCheck("dns-coordinator", cfg.coordinator.url)
            relayCallback(cfg)

            for (url in cfg.transport.relayUrls) {
                val uri = runCatching { URI.create(url) }.getOrNull()
                val relayHost = uri?.host?.removeSurrounding("[", "]")
                val relayPort = if (uri?.port == -1) 443 else uri?.port ?: 443
                dnsCheck("dns-relay", url)
                if (uri != null && relayHost != null && tcpReachable(relayHost, relayPort, 3_000)) {
                    pass("relay", "$url tcp/$relayPort")
                } else {
                    warn("relay", "$url không phản hồi tcp/$relayPort")
                }
                if (relayHost != null) {
                    udpCheck(relayHost, 7842)
                }
            }
        }

        println(if (failures == 0) "Kết quả: OK" else "Kết quả: $failures mục FAIL")
        return if (failures == 0) 0 else 1
    }

    private enum class Check { PASS, WARN, FAIL }

    private fun healthz(cfg: AgentConfig): Pair<Check, String> = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val request = HttpRequest.newBuilder(URI.create(cfg.coordinator.url.trimEnd('/') + "/healthz"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        when {
            response.statusCode() == 200 -> Check.PASS to "healthz ${response.body().take(32)}"
            cfg.coordinator.insecureDev -> Check.WARN to "HTTP ${response.statusCode()}"
            else -> Check.FAIL to "HTTP ${response.statusCode()}"
        }
    }.getOrElse {
        if (cfg.coordinator.insecureDev) Check.WARN to "không kết nối được: ${it.message}"
        else Check.FAIL to "không kết nối được: ${it.message}"
    }

    private fun dnsCheck(name: String, url: String) {
        val host = runCatching { URI.create(url).host?.removeSurrounding("[", "]") }.getOrNull()
        if (host == null) {
            warn(name, "URL không hợp lệ: $url")
            return
        }
        val addresses = runCatching { java.net.InetAddress.getAllByName(host).joinToString(",") { it.hostAddress } }
        addresses.onSuccess { pass(name, "$host → $it") }
            .onFailure { fail(name, "không phân giải được $host: ${it.message}") }
    }

    private fun bedrockCheck(target: String) {
        val parsed = runCatching {
            BedrockGateway.validateTarget(target)
            AgentConfig.parseHostPort(target)
        }.getOrElse {
            fail("bedrock", "đích không hợp lệ: $target")
            return
        }
        val (host, port) = parsed
        val sent = runCatching {
            val address = java.net.InetAddress.getByName(host)
            java.net.DatagramSocket().use { socket ->
                socket.send(java.net.DatagramPacket(ByteArray(1), 1, address, port))
            }
        }
        if (sent.isSuccess) {
            warn("bedrock", "$target đã gửi gói UDP thử (RakNet không bắt tay ở doctor)")
        } else {
            fail("bedrock", "$target không gửi được: ${sent.exceptionOrNull()?.message}")
        }
    }

    private fun udpCheck(host: String, port: Int) {
        val sent = runCatching {
            val address = java.net.InetAddress.getByName(host)
            java.net.DatagramSocket().use { socket ->
                socket.send(java.net.DatagramPacket(ByteArray(1), 1, address, port))
            }
        }
        if (sent.isSuccess) {
            warn("relay-udp", "$host:$port đã gửi gói thử (UDP không bắt tay, không xác minh được)")
        } else {
            warn("relay-udp", "$host:$port không gửi được: ${sent.exceptionOrNull()?.message}")
        }
    }

    private fun relayCallback(cfg: AgentConfig) {
        val state = runCatching {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val request = HttpRequest.newBuilder(URI.create(cfg.coordinator.url.trimEnd('/') + "/v1/relay/authorize"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }.getOrElse { -1 }
        when (state) {
            401, 403 -> pass("relay-callback", "route tồn tại (HTTP $state khi thiếu token)")
            404 -> warn("relay-callback", "route /v1/relay/authorize không tồn tại")
            -1 -> warn("relay-callback", "không gọi được coordinator")
            else -> warn("relay-callback", "HTTP $state")
        }
    }

    private fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean =
        port in 1..65535 && runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        }.isSuccess

    private fun pass(name: String, detail: String) = println("PASS  $name: $detail")

    private fun warn(name: String, detail: String) = println("WARN  $name: $detail")

    private fun fail(name: String, detail: String) {
        failures += 1
        println("FAIL  $name: $detail")
    }
}
