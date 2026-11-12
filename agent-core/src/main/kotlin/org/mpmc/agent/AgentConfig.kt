package org.mpmc.agent

import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.mpmc.transport.RelayMode
import java.nio.file.Files
import java.nio.file.Path

data class CoordinatorSettings(
    val url: String,
    val insecureDev: Boolean = false,
)

data class TransportSettings(
    val alpn: String = "mpmc/1",
    val relayMode: String = "custom",
    val relayUrls: List<String> = emptyList(),
    val connectTimeoutSec: Long = 20,
    val handshakeTimeoutSec: Long = 10,
) {
    fun relayModeValue(): RelayMode = when (relayMode) {
        "n0" -> RelayMode.N0
        "disabled" -> RelayMode.Disabled
        else -> RelayMode.Custom(relayUrls)
    }
}

data class HostSettings(
    val target: String,
    val maxMembers: Int = 8,
    val maxConcurrentStreams: Int = 20,
    val approval: String = "prompt",
    val maxStreamsPerTicket: Int = 4,
)

data class JoinSettings(
    val listen: String = "127.0.0.1:0",
)

data class AgentConfig(
    val dataDir: Path,
    val name: String = "mpmc",
    val coordinator: CoordinatorSettings,
    val transport: TransportSettings = TransportSettings(),
    val host: HostSettings = HostSettings(target = "127.0.0.1:25565"),
    val join: JoinSettings = JoinSettings(),
) {
    fun validate() {
        val coordinatorUrl = coordinator.url
        require(coordinatorUrl.startsWith("https://") || isLoopbackHttp(coordinatorUrl)) {
            "coordinator.url phải là https:// (http:// chỉ chấp nhận loopback)"
        }
        require(join.listen.startsWith("127.0.0.1:")) {
            "join.listen phải bắt đầu bằng 127.0.0.1: (không bind ra ngoài)"
        }
        parseHostPort(join.listen)
        require(host.target.isNotBlank()) { "host.target không được rỗng" }
        val (targetHost, targetPort) = parseHostPort(host.target)
        require(targetHost != "0.0.0.0" && targetHost != "::") { "host.target không được là địa chỉ wildcard" }
        require(targetPort in 1..65535) { "host.target port không hợp lệ" }
        require(host.maxMembers in 1..64) { "host.max_members phải trong 1..64" }
        require(host.maxConcurrentStreams in 1..200) { "host.max_concurrent_streams phải trong 1..200" }
        require(host.approval == "prompt" || host.approval == "invite") {
            "host.approval phải là prompt hoặc invite"
        }
        require(host.maxStreamsPerTicket in 1..16) { "host.max_streams_per_ticket phải trong 1..16" }
        for (url in transport.relayUrls) {
            require(url.startsWith("https://") || isLoopbackHttp(url)) {
                "transport.relay_urls phải là https:// (http:// chỉ chấp nhận loopback)"
            }
        }
        require(transport.connectTimeoutSec > 0) { "transport.connect_timeout_sec phải dương" }
        require(transport.handshakeTimeoutSec > 0) { "transport.handshake_timeout_sec phải dương" }
    }

    fun relayMode(): RelayMode = when (transport.relayMode) {
        "n0" -> RelayMode.N0
        "disabled" -> RelayMode.Disabled
        else -> RelayMode.Custom(transport.relayUrls)
    }

    companion object {
        val DEFAULT_PATH_NAME = "config.toml"

        fun load(path: Path): AgentConfig {
            val parsed = Toml.parse(path)
            if (parsed.hasErrors()) {
                val messages = parsed.errors().joinToString("; ") { it.toString() }
                error("config.toml không hợp lệ: $messages")
            }
            return fromToml(parsed, path.parent ?: Path.of("."))
        }

        fun loadOrDefault(dataDir: Path): AgentConfig {
            val path = dataDir.resolve(DEFAULT_PATH_NAME)
            if (!Files.exists(path)) return defaultFor(dataDir)
            return load(path)
        }

        fun defaultFor(dataDir: Path): AgentConfig = AgentConfig(
            dataDir = dataDir,
            coordinator = CoordinatorSettings(url = "http://127.0.0.1:8080", insecureDev = true),
            transport = TransportSettings(relayMode = "disabled"),
        )

        fun fromToml(toml: TomlParseResult, dataDir: Path): AgentConfig {
            val relayUrls = toml.getArray("transport.relay_urls")
                ?.toList()
                ?.mapNotNull { it as? String }
                ?: emptyList()
            return AgentConfig(
                dataDir = dataDir,
                name = toml.getString("agent.name") ?: "mpmc",
                coordinator = CoordinatorSettings(
                    url = toml.getString("coordinator.url") ?: "http://127.0.0.1:8080",
                    insecureDev = toml.getBoolean("coordinator.insecure_dev") ?: false,
                ),
                transport = TransportSettings(
                    alpn = toml.getString("transport.alpn") ?: "mpmc/1",
                    relayMode = toml.getString("transport.relay_mode") ?: "custom",
                    relayUrls = relayUrls,
                    connectTimeoutSec = toml.getLong("transport.connect_timeout_sec") ?: 20,
                    handshakeTimeoutSec = toml.getLong("transport.handshake_timeout_sec") ?: 10,
                ),
                host = HostSettings(
                    target = toml.getString("host.target") ?: "127.0.0.1:25565",
                    maxMembers = (toml.getLong("host.max_members") ?: 8).toInt(),
                    maxConcurrentStreams = (toml.getLong("host.max_concurrent_streams") ?: 20).toInt(),
                    approval = toml.getString("host.approval") ?: "prompt",
                    maxStreamsPerTicket = (toml.getLong("host.max_streams_per_ticket") ?: 4).toInt(),
                ),
                join = JoinSettings(
                    listen = toml.getString("join.listen") ?: "127.0.0.1:0",
                ),
            )
        }

        fun isLoopbackHttp(url: String): Boolean {
            if (!url.startsWith("http://")) return false
            val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
            return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
        }

        fun parseHostPort(value: String): Pair<String, Int> {
            val idx = value.lastIndexOf(':')
            require(idx > 0) { "địa chỉ phải có dạng host:port: $value" }
            val host = value.substring(0, idx)
            val port = value.substring(idx + 1).toIntOrNull()
                ?: error("port không hợp lệ: $value")
            return host to port
        }
    }
}
