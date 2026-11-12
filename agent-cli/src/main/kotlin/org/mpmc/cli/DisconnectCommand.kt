package org.mpmc.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(name = "disconnect", mixinStandardHelpOptions = true, description = ["Dừng phiên host/join đang chạy."])
class DisconnectCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: MpmcCommand

    @Option(names = ["--control-port"], description = ["Cổng điều khiển cục bộ (mặc định 45123)."])
    var controlPort: Int = ControlServer.DEFAULT_PORT

    override fun call(): Int {
        val response = ControlServer.request("stop", controlPort)
        if (response == null) {
            println("Không có phiên nào đang chạy trên 127.0.0.1:$controlPort.")
            return 1
        }
        println(response)
        return 0
    }
}
