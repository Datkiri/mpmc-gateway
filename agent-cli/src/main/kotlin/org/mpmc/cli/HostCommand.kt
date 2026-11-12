package org.mpmc.cli

import kotlinx.coroutines.runBlocking
import org.mpmc.agent.AgentConfig
import org.mpmc.agent.CoordinatorClient
import org.mpmc.agent.DevTicket
import org.mpmc.agent.HostRoom
import org.mpmc.agent.HostService
import org.mpmc.agent.JwksCache
import org.mpmc.agent.KeyStore
import org.mpmc.agent.SessionStore
import org.mpmc.agent.gateway.BedrockGateway
import org.mpmc.agent.gateway.ViaProxyRelease
import org.mpmc.protocol.CreateRoomRequest
import org.mpmc.transport.IrohTransport
import org.mpmc.transport.TransportConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(
    name = "host",
    mixinStandardHelpOptions = true,
    description = ["Mở phòng và chuyển tiếp lưu lượng tới server Minecraft."],
)
class HostCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: MpmcCommand

    @Option(names = ["--target"], description = ["Địa chỉ server đích host:port."])
    var target: String? = null

    @Option(names = ["--bedrock"], description = ["Server Bedrock host:port; tự chạy ViaProxy và trỏ host vào cổng đó."])
    var bedrock: String? = null

    @Option(names = ["--gateway-bind"], description = ["Cổng Java do ViaProxy mở (mặc định ${ViaProxyRelease.DEFAULT_GATEWAY_BIND})."])
    var gatewayBind: String = ViaProxyRelease.DEFAULT_GATEWAY_BIND

    @Option(names = ["--bedrock-version"], description = ["Phiên bản Bedrock đích (mặc định '${ViaProxyRelease.DEFAULT_TARGET_VERSION}')."])
    var bedrockVersion: String = ViaProxyRelease.DEFAULT_TARGET_VERSION

    @Option(names = ["--code"], description = ["Mã phòng tự chọn (chỉ dùng với --insecure-dev-ticket)."])
    var code: String? = null

    @Option(names = ["--relay-mode"], description = ["n0 | disabled | custom (mặc định theo config)."])
    var relayMode: String? = null

    @Option(names = ["--relay-url"], description = ["URL relay (dùng nhiều lần được)."])
    var relayUrls: MutableList<String> = mutableListOf()

    @Option(names = ["--bind"], description = ["Địa chỉ bind của iroh, ví dụ 0.0.0.0:0."])
    var bindAddr: String? = null

    @Option(names = ["--max-members"], description = ["Giới hạn thành viên."])
    var maxMembers: Int? = null

    @Option(names = ["--insecure-dev-ticket"], description = ["Chế độ dev: ticket tự ký, không cần Coordinator."])
    var devMode: Boolean = false

    @Option(names = ["--approve-all"], description = ["Tự động chấp nhận mọi yêu cầu vào phòng."])
    var approveAll: Boolean = false

    @Option(names = ["--control-port"], description = ["Cổng điều khiển cục bộ (mặc định 45123)."])
    var controlPort: Int = ControlServer.DEFAULT_PORT

    override fun call(): Int {
        if (bedrock != null && target != null) {
            System.err.println("Chỉ dùng một trong --target hoặc --bedrock.")
            return 2
        }
        val gateway = bedrock?.let { BedrockGateway(root.dataDir) }
        if (gateway != null) {
            runCatching {
                if (!gateway.isInstalled()) {
                    println("Chưa có ViaProxy; tải bản ${ViaProxyRelease.VERSION} (pin SHA-256)…")
                    gateway.install { println(it) }
                }
                gateway.start(gatewayBind, bedrock!!, bedrockVersion)
            }.onFailure {
                gateway.close()
                System.err.println("Không khởi động được gateway Bedrock: ${it.message}")
                return 2
            }
            println("Gateway Java→Bedrock: $gatewayBind → $bedrock ($bedrockVersion) | log ${gateway.logPath}")
        }
        val hostTarget = gatewayBind.takeIf { gateway != null } ?: target
        try {
            return runHost(hostTarget)
        } finally {
            gateway?.close()
        }
    }

    private fun runHost(hostTarget: String?): Int {
        val config = root.config { base ->
            base.copy(
                host = base.host.copy(
                    target = hostTarget ?: base.host.target,
                    maxMembers = maxMembers ?: base.host.maxMembers,
                ),
                transport = base.transport.copy(
                    relayMode = relayMode ?: base.transport.relayMode,
                    relayUrls = if (relayUrls.isEmpty()) base.transport.relayUrls else relayUrls.toList(),
                ),
            )
        }
        val identity = KeyStore.loadOrCreate(config.dataDir)
        val status = SessionStore(config.dataDir)
        val transport = IrohTransport()
        val peer = runBlocking {
            transport.bind(
                TransportConfig(
                    secretKey = identity.seed,
                    alpn = config.transport.alpn,
                    relayMode = config.relayMode(),
                    bindAddr = bindAddr,
                ),
            )
        }
        val room: HostRoom
        val coordinator: CoordinatorClient?
        val verifier: JwksCache?
        val events: kotlinx.coroutines.flow.Flow<org.mpmc.protocol.WsEvent>?
        if (devMode) {
            room = HostRoom(DevTicket.ROOM_ID, code ?: DevTicket.CODE, hostToken = "dev")
            coordinator = null
            verifier = null
            events = null
        } else {
            val client = CoordinatorClient(config.coordinator.url, identity)
            val created = runBlocking {
                client.authenticate()
                client.createRoom(
                    CreateRoomRequest(
                        maxMembers = config.host.maxMembers,
                        approvalMode = config.host.approval,
                        label = config.name,
                    ),
                )
            }
            room = HostRoom(created.roomId, created.code, created.hostToken, created.inviteToken)
            coordinator = client
            verifier = JwksCache({ client.jwksRawKeys() }, config.dataDir.resolve("jwks.json"))
            events = client.events()
            println("Phòng: ${created.code} (hết hạn ${created.expiresAt})")
        }
        val service = HostService(
            config = config,
            identity = identity,
            peer = peer,
            room = room,
            status = status,
            coordinator = coordinator,
            verifier = verifier,
            events = events,
            devMode = devMode,
            approvalDecider = if (devMode) null else approvalDecider(approveAll),
        )
        println("Endpoint host: ${identity.endpointId}")
        println("Đích: ${config.host.target} | Relay: ${config.transport.relayMode} | Dev ticket: $devMode")
        println("Nhấn Ctrl+C hoặc chạy 'mpmc disconnect' để dừng.")
        return runTunnel(status, service, service::run, controlPort)
    }

    private fun approvalDecider(autoApprove: Boolean): suspend (org.mpmc.protocol.WsEvent) -> Boolean = { event ->
        if (autoApprove) {
            true
        } else {
            val id = event.data["endpointId"]?.toString()?.take(12) ?: "?"
            print("Cho phép endpoint ${id}… vào phòng? [y/N] ")
            System.out.flush()
            val answer = runCatching { readlnOrNull() }.getOrNull()?.trim()?.lowercase()
            answer == "y" || answer == "yes"
        }
    }
}
