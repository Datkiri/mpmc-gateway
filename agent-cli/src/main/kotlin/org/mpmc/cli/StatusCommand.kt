package org.mpmc.cli

import org.mpmc.agent.SessionStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(name = "status", mixinStandardHelpOptions = true, description = ["Xem trạng thái phiên hiện tại."])
class StatusCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: MpmcCommand

    @Option(names = ["--json"], description = ["In JSON thay vì văn bản."])
    var json: Boolean = false

    override fun call(): Int {
        val state = SessionStore(root.dataDir).read()
        if (state == null) {
            println("Không có phiên nào (${root.dataDir.resolve("session.json")}).")
            return 1
        }
        if (json) {
            println(SessionJson.encode(state))
            return 0
        }
        println("Vai trò     : ${state.role}")
        println("Mã phòng    : ${state.roomCode ?: "-"}")
        println("Endpoint    : ${state.endpointId}")
        println("Trạng thái  : ${state.state}")
        println("Đường truyền: ${state.path ?: "-"}")
        println("RTT         : ${state.rttMs?.let { "$it ms" } ?: "-"}")
        println("Relay RTT   : ${state.relayRttMs?.let { "$it ms" } ?: "-"}")
        println("Thiết lập   : ${state.setupMs?.let { "$it ms" } ?: "-"}")
        println("Proxy cục bộ: ${state.localAddress ?: "-"}")
        println("Người chơi  : ${state.players ?: "-"}")
        println("Dữ liệu     : up=${state.bytesUp} down=${state.bytesDown}")
        println("TCP mở      : ${state.openTcp}")
        state.error?.let { println("Lỗi         : $it") }
        return 0
    }
}
