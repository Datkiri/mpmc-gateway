package org.mpmc.cli

import org.mpmc.agent.gateway.BedrockGateway
import org.mpmc.agent.gateway.ViaProxyRelease
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(
    name = "gateway",
    mixinStandardHelpOptions = true,
    description = ["Quản lý cổng chuyển đổi Java ↔ Bedrock (ViaProxy)."],
    subcommands = [GatewayInstallCommand::class, GatewayStatusCommand::class],
)
class GatewayCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: MpmcCommand

    override fun call(): Int {
        picocli.CommandLine(this).usage(System.out)
        return 0
    }
}

@Command(name = "install", mixinStandardHelpOptions = true, description = ["Tải ViaProxy đã pin phiên bản + SHA-256, hoặc cài bản mpmc-patched từ tệp có sẵn."])
class GatewayInstallCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: GatewayCommand

    @Option(names = ["--force"], description = ["Tải lại kể cả khi tệp đã tồn tại."])
    var force: Boolean = false

    @Option(
        names = ["--from"],
        description = ["Cài từ jar có sẵn (ví dụ bản mpmc-patched build ngoài gói phát hành) thay vì tải."],
    )
    var from: java.nio.file.Path? = null

    @Option(
        names = ["--sha256"],
        description = ["SHA-256 mong đợi cho --from; mặc định là hash của bản mpmc-patched đã kiểm chứng."],
    )
    var sha256: String = ViaProxyRelease.PATCHED_SHA256

    override fun call(): Int {
        val gateway = BedrockGateway(parent.root.dataDir)
        return runCatching {
            val source = from
            if (source != null) {
                gateway.installLocal(source, sha256) { println(it) }
            } else {
                gateway.install(force) { println(it) }
            }
            println("Gateway ${gateway.buildLabel()}: ${gateway.jar}")
            0
        }.getOrElse {
            System.err.println("Cài gateway thất bại: ${it.message}")
            2
        }
    }
}

@Command(
    name = "status",
    aliases = ["verify"],
    mixinStandardHelpOptions = true,
    description = ["Kiểm tra gateway đã cài trong thư mục dữ liệu (alias: verify)."],
)
class GatewayStatusCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: GatewayCommand

    override fun call(): Int {
        val gateway = BedrockGateway(parent.root.dataDir)
        println("ViaProxy ${ViaProxyRelease.VERSION} / mpmc-patched ${ViaProxyRelease.PATCHED_VERSION}")
        println("Tệp: ${gateway.jar}")
        println("Bản cài: ${gateway.buildLabel()}")
        println("Trạng thái: ${gateway.verify()}")
        println("Java: ${BedrockGateway.javaExecutable()}")
        println("Bind mặc định: ${ViaProxyRelease.DEFAULT_GATEWAY_BIND}")
        println("Phiên bản đích mặc định: ${ViaProxyRelease.DEFAULT_TARGET_VERSION}")
        return if (gateway.isInstalled()) 0 else 1
    }
}
