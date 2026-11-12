package org.mpmc.cli

import org.mpmc.agent.AgentConfig
import org.mpmc.agent.CoordinatorClient
import org.mpmc.agent.DevTicket
import org.mpmc.agent.JoinService
import org.mpmc.agent.KeyStore
import org.mpmc.agent.SessionStore
import org.mpmc.transport.IrohTransport
import org.mpmc.transport.TransportConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(
    name = "join",
    mixinStandardHelpOptions = true,
    description = ["Kết nối tới phòng và mở proxy cục bộ cho Minecraft."],
)
class JoinCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: MpmcCommand

    @Option(names = ["--code"], description = ["Mã phòng."])
    var code: String? = null

    @Option(names = ["--invite-token"], description = ["Invite token (không bắt buộc nếu host duyệt tay)."])
    var inviteToken: String? = null

    @Option(names = ["--dev-host-endpoint"], description = ["Endpoint id của host (chỉ dùng với --insecure-dev-ticket)."])
    var devHostEndpoint: String? = null

    @Option(names = ["--insecure-dev-ticket"], description = ["Chế độ dev: tự ký ticket, không cần Coordinator."])
    var devMode: Boolean = false

    @Option(names = ["--listen"], description = ["Địa chỉ proxy cục bộ, mặc định 127.0.0.1:0."])
    var listen: String? = null

    @Option(names = ["--direct"], description = ["Địa chỉ trực tiếp của host ip:port (dev/nether-only)."])
    var direct: MutableList<String> = mutableListOf()

    @Option(names = ["--relay-url"], description = ["URL relay gợi ý khi dial host."])
    var relayUrl: String? = null

    @Option(names = ["--relay-mode"], description = ["n0 | disabled | custom."])
    var relayMode: String? = null

    @Option(names = ["--relay-urls"], description = ["URL relay của endpoint (dùng nhiều lần được)."])
    var relayUrls: MutableList<String> = mutableListOf()

    @Option(names = ["--control-port"], description = ["Cổng điều khiển cục bộ (mặc định 45123)."])
    var controlPort: Int = ControlServer.DEFAULT_PORT

    override fun call(): Int {
        val roomCode = code ?: System.getenv("MPMC_ROOM_CODE") ?: if (devMode) DevTicket.CODE else {
            System.err.println("Thiếu --code hoặc MPMC_ROOM_CODE")
            return 1
        }
        if (devMode && devHostEndpoint.isNullOrBlank()) {
            System.err.println("Chế độ dev cần --dev-host-endpoint")
            return 1
        }
        val config = root.config { base ->
            base.copy(
                join = base.join.copy(listen = listen ?: base.join.listen),
                transport = base.transport.copy(
                    relayMode = relayMode ?: base.transport.relayMode,
                    relayUrls = if (relayUrls.isEmpty()) base.transport.relayUrls else relayUrls.toList(),
                ),
            )
        }
        val identity = KeyStore.loadOrCreate(config.dataDir)
        val status = SessionStore(config.dataDir)
        val transport = IrohTransport()
        val peer = kotlinx.coroutines.runBlocking {
            transport.bind(
                TransportConfig(
                    secretKey = identity.seed,
                    alpn = config.transport.alpn,
                    relayMode = config.relayMode(),
                ),
            )
        }
        val coordinator = if (devMode) null else CoordinatorClient(config.coordinator.url, identity)
        val service = JoinService(
            config = config,
            identity = identity,
            peer = peer,
            code = roomCode,
            inviteToken = inviteToken ?: System.getenv("MPMC_INVITE_TOKEN"),
            status = status,
            coordinator = coordinator,
            devMode = devMode,
            devHostEndpointId = devHostEndpoint,
            relayUrlHint = relayUrl ?: config.transport.relayUrls.firstOrNull(),
            directAddresses = direct.toList(),
        )
        return runTunnel(status, service, service::run, controlPort)
    }
}
