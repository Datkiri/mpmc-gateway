package org.mpmc.cli

import org.mpmc.agent.AgentConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.util.concurrent.Callable

@Command(name = "config", mixinStandardHelpOptions = true, description = ["Xem hoặc tạo config.toml."])
class ConfigCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: MpmcCommand

    @Option(names = ["--init"], description = ["Tạo config.toml mặc định nếu chưa có."])
    var init: Boolean = false

    @Option(names = ["--path"], description = ["In đường dẫn file cấu hình."])
    var path: Boolean = false

    override fun call(): Int {
        val file = root.dataDir.resolve(AgentConfig.DEFAULT_PATH_NAME)
        if (path) {
            println(file)
            return 0
        }
        if (init && !Files.exists(file)) {
            Files.createDirectories(root.dataDir)
            Files.writeString(file, DEFAULT_TOML)
            println("Đã tạo $file")
            return 0
        }
        val config = runCatching { AgentConfig.loadOrDefault(root.dataDir) }
            .getOrElse {
                System.err.println("Cấu hình không hợp lệ: ${it.message}")
                return 1
            }
        println("File        : $file${if (Files.exists(file)) "" else " (chưa tồn tại, dùng mặc định)"}")
        println("Agent       : ${config.name}")
        println("Coordinator : ${config.coordinator.url} (insecureDev=${config.coordinator.insecureDev})")
        println("ALPN        : ${config.transport.alpn}")
        println("Relay mode  : ${config.transport.relayMode} ${config.transport.relayUrls}")
        println("Target      : ${config.host.target}")
        println("Max members : ${config.host.maxMembers}")
        println("Streams     : ${config.host.maxConcurrentStreams}/${config.host.maxStreamsPerTicket}")
        println("Approval    : ${config.host.approval}")
        println("Join listen : ${config.join.listen}")
        return 0
    }

    companion object {
        private val DEFAULT_TOML = """
            [agent]
            name = "mpmc"

            [coordinator]
            url = "https://coordinator.example.com"
            insecure_dev = false

            [transport]
            alpn = "mpmc/1"
            relay_mode = "custom"
            relay_urls = ["https://relay.example.com"]
            connect_timeout_sec = 20
            handshake_timeout_sec = 10

            [host]
            target = "127.0.0.1:25565"
            max_members = 8
            max_concurrent_streams = 20
            approval = "prompt"
            max_streams_per_ticket = 4

            [join]
            listen = "127.0.0.1:0"
        """.trimIndent() + "\n"
    }
}
