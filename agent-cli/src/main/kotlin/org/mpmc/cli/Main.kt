package org.mpmc.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

const val CLI_VERSION = "0.1.0"

@Command(
    name = "mpmc",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
    description = ["P2P tunnel cho Minecraft Java: host mở cổng, join kết nối qua iroh."],
    subcommands = [
        HostCommand::class,
        JoinCommand::class,
        StatusCommand::class,
        DisconnectCommand::class,
        ConfigCommand::class,
        GatewayCommand::class,
        DoctorCommand::class,
        VersionCommand::class,
    ],
)
class MpmcCommand : Callable<Int> {
    @Option(
        names = ["--data-dir"],
        description = ["Thư mục dữ liệu (mặc định: ~/mpmc)."],
        scope = CommandLine.ScopeType.INHERIT,
    )
    var dataDir: Path = defaultDataDir()

    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }

    fun config(overrides: (org.mpmc.agent.AgentConfig) -> org.mpmc.agent.AgentConfig = { it }): org.mpmc.agent.AgentConfig {
        val loaded = org.mpmc.agent.AgentConfig.loadOrDefault(dataDir)
        val withData = loaded.copy(dataDir = dataDir)
        return overrides(withData).also { it.validate() }
    }

    companion object {
        fun defaultDataDir(): Path {
            val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            return Path.of(base, "mpmc")
        }
    }
}

class VersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("mpmc $CLI_VERSION")
}

@Command(name = "version", mixinStandardHelpOptions = true, description = ["In phiên bản."])
class VersionCommand : Callable<Int> {
    override fun call(): Int {
        println("mpmc $CLI_VERSION (protocol ${org.mpmc.protocol.FRAME_VERSION})")
        return 0
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(MpmcCommand()).execute(*args))
}
