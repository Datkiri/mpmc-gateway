package org.mpmc.agent.gateway

import org.mpmc.agent.AgentConfig
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * ViaProxy là tiến trình Java bên ngoài (GPL-3.0) dịch Java Edition ↔ Bedrock Edition.
 * mpmc chỉ tải bản phát hành chính thức đã pin SHA-256 và chạy nó như một tiến trình con,
 * không nhúng mã nguồn hay binary vào gói phát hành.
 */
object ViaProxyRelease {
    const val VERSION = "3.4.14"
    const val FILE_NAME = "ViaProxy-$VERSION.jar"
    const val DOWNLOAD_URL = "https://github.com/ViaVersion/ViaProxy/releases/download/v$VERSION/$FILE_NAME"
    const val SHA256 = "2894bbfb2f4342f8fde887af6c689313efd5b0e46a8006775eea0e21e2948ab5"

    /**
     * Bản build cục bộ của mpmc: ViaProxy 3.4.14 + ViaBedrock 0.0.31-mpmc.18 (GPL-3.0), giữ ngoài
     * gói phát hành. Bản này bổ sung creative inventory qua ItemStackRequest, đặt/đào block qua
     * transaction nhúng trong PlayerAuthInput (Bedrock 1.26.40+), slot ARMOR Body, packet
     * locator_bar (341), layout RESPAWN khi CHANGE_DIMENSION, replay packet clientbound
     * tới trước PLAY qua đầy đủ pipeline BedrockProtocol, case tường minh cho tham số lệnh
     * CodeBuilderArgs (Bedrock 1.26.51), và audit từng packet 2 chiều (-Dmpmc.audit=true) với
     * bảng phân loại tường minh cho toàn bộ 169 clientbound Bedrock + 69 serverbound Java:
     * không còn packet nào bị huỷ im lặng. Các bản .7-.12 sửa tương thích client Java 1.21.1
     * (USE_ITEM_ON typed values, ACCEPT_TELEPORTATION replay, ack inventory creative) và loại
     * LOGIN_FINISHED trùng do nhánh "Skipping LOGIN state" của upstream.
     * `.13` xếp hàng ITEM_STACK_REQUEST theo (container, slot) để BDS cấp netId trước khi
     * click kế tiếp (status 49). `.14` sửa offhand wire slot 1, phân loại item không equippable
     * trong Armor/Offhand (BDS status 55) thay vì gửi request bị từ chối, và alias
     * spectral_arrow / filled_map sang item Bedrock thật để không còn drop im lặng.
     * `.16` clamp mọi field số lượng của ITEM_STACK_REQUEST vào [1,64] (hết BDS kick
     * PacketMalformed). `.17` clamp count theo max stack size riêng của từng item (bảng 288
     * entry sinh từ minecraft-data Bedrock 1.26.51) để hết status 51 InvalidAdjustedAmount.
     * `.18` sửa sequence USE_ITEM_ON cho Java 1.21.1 (đọc VarInt còn sót trong inputBuffer,
     * hết BlockChangedAck(0) / ghost block), thay handler INTERACT action=0 bị ViaBackwards
     * huỷ bằng bản sinh click-position zero, và null-guard EntityTracker.getEntityByJid()
     * để click entity không tồn tại không còn NPE kick cả phiên.
     */
    const val PATCHED_VERSION = "3.4.14-mpmc.18"
    const val PATCHED_SHA256 = "bdeab29f84e9bfa81714da15da49e611438d04e71aa93d088ec0854bdf4f78f8"

    const val DEFAULT_TARGET_VERSION = "Bedrock 1.26.51"
    const val DEFAULT_GATEWAY_BIND = "127.0.0.1:25568"
}

class BedrockGateway(private val dataDir: Path) : AutoCloseable {
    private var process: Process? = null
    private var shutdownHook: Thread? = null

    val directory: Path = dataDir.resolve("gateway")
    val jar: Path = directory.resolve(ViaProxyRelease.FILE_NAME)
    val logPath: Path = directory.resolve("viaproxy.log")

    fun isInstalled(): Boolean =
        Files.isRegularFile(jar) && runCatching { sha256(jar) in KNOWN_SHA256 }.getOrDefault(false)

    fun buildLabel(): String = when {
        !Files.isRegularFile(jar) -> "chưa cài"
        sha256(jar) == ViaProxyRelease.PATCHED_SHA256 -> "mpmc-patched ${ViaProxyRelease.PATCHED_VERSION}"
        sha256(jar) == ViaProxyRelease.SHA256 -> "chính thức ${ViaProxyRelease.VERSION}"
        else -> "không rõ nguồn"
    }

    fun verify(): String = when {
        !Files.isRegularFile(jar) -> "thiếu tệp ${jar.fileName}"
        sha256(jar) !in KNOWN_SHA256 -> "SHA-256 không khớp"
        else -> "SHA-256 khớp (${buildLabel()})"
    }

    fun install(force: Boolean = false, progress: (String) -> Unit = {}): Path {
        Files.createDirectories(directory)
        if (!force && isInstalled()) {
            progress("Đã có ${jar.fileName} (SHA-256 khớp).")
            return jar
        }
        val temp = directory.resolve("${jar.fileName}.part")
        progress("Đang tải ${ViaProxyRelease.DOWNLOAD_URL}")
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(ViaProxyRelease.DOWNLOAD_URL))
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build()
        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofFile(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ),
        )
        check(response.statusCode() == 200) { "tải ViaProxy thất bại: HTTP ${response.statusCode()}" }
        val actual = sha256(temp)
        if (actual != ViaProxyRelease.SHA256) {
            Files.deleteIfExists(temp)
            error("SHA-256 không khớp: nhận $actual, cần ${ViaProxyRelease.SHA256}")
        }
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        progress("Đã cài ${jar.fileName} (${Files.size(jar)} byte).")
        return jar
    }

    /**
     * Cài một jar gateway có sẵn trên máy (bản mpmc-patched hoặc bản chính thức). Bắt buộc kiểm
     * SHA-256 trước khi dùng; không tự tải và không nhúng jar vào gói phát hành mpmc.
     */
    fun installLocal(source: Path, expectedSha256: String, progress: (String) -> Unit = {}): Path {
        check(Files.isRegularFile(source)) { "không thấy tệp $source" }
        val actual = sha256(source)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            error("SHA-256 không khớp: nhận $actual, cần $expectedSha256")
        }
        Files.createDirectories(directory)
        val temp = directory.resolve("${jar.fileName}.part")
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        progress("Đã cài ${jar.fileName} (${Files.size(jar)} byte, SHA-256 $actual).")
        return jar
    }

    fun start(
        bind: String = ViaProxyRelease.DEFAULT_GATEWAY_BIND,
        target: String,
        targetVersion: String = ViaProxyRelease.DEFAULT_TARGET_VERSION,
        timeout: Duration = Duration.ofSeconds(60),
    ): Process {
        validateBind(bind)
        validateTarget(target)
        check(isInstalled()) { "ViaProxy chưa cài hoặc sai SHA-256 tại $jar; chạy 'mpmc gateway install'" }
        check(process?.isAlive != true) { "gateway đang chạy" }
        Files.createDirectories(directory)
        val command = listOf(
            javaExecutable(),
            "-jar", jar.toAbsolutePath().toString(),
            "cli",
            "--bind-address", bind,
            "--target-address", target,
            "--target-version", targetVersion,
            "--auth-method", "NONE",
            // Lỗi RESPAWN/CHANGE_DIMENSION đã được sửa tại gốc trong fork mpmc.3 (ghi
            // Types.VAR_INT + OPTIONAL_VAR_INT). Giữ cờ này như hàng rào an toàn cho các
            // packet dịch lỗi khác, không còn dùng để bỏ rơi RESPAWN.
            "--ignore-protocol-translation-errors", "true",
        )
        val started = ProcessBuilder(command)
            .directory(directory.toAbsolutePath().toFile())
            .redirectErrorStream(true)
            .redirectOutput(logPath.toAbsolutePath().toFile())
            .start()
        process = started
        shutdownHook = Thread { started.destroyForcibly() }.also {
            Runtime.getRuntime().addShutdownHook(it)
        }
        try {
            awaitReady(bind, started, timeout)
        } catch (e: Exception) {
            stop()
            throw e
        }
        return started
    }

    fun stop() {
        shutdownHook?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
        shutdownHook = null
        val running = process ?: return
        process = null
        running.destroy()
        if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroyForcibly()
    }

    override fun close() = stop()

    private fun awaitReady(bind: String, running: Process, timeout: Duration) {
        val (host, port) = AgentConfig.parseHostPort(bind)
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (!running.isAlive) {
                error("ViaProxy thoát sớm (mã ${running.exitValue()}).\n${logTail()}")
            }
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 500) }
            }.isSuccess
            if (ok) return
            Thread.sleep(250)
        }
        error("ViaProxy không mở $bind sau ${timeout.seconds}s.\n${logTail()}")
    }

    private fun logTail(lines: Int = 15): String = runCatching {
        Files.readAllLines(logPath).takeLast(lines).joinToString("\n")
    }.getOrDefault("(không đọc được ${logPath})")

    companion object {
        val KNOWN_SHA256 = setOf(ViaProxyRelease.SHA256, ViaProxyRelease.PATCHED_SHA256)

        fun validateBind(bind: String) {
            val (host, port) = runCatching { AgentConfig.parseHostPort(bind) }.getOrElse {
                throw IllegalArgumentException("gateway bind không hợp lệ: $bind")
            }
            require(host == "127.0.0.1" || host == "localhost" || host == "[::1]") {
                "gateway bind phải là loopback: $bind"
            }
            require(port in 1..65535) { "gateway port không hợp lệ: $bind" }
        }

        fun validateTarget(target: String) {
            val (host, port) = runCatching { AgentConfig.parseHostPort(target) }.getOrElse {
                throw IllegalArgumentException("đích Bedrock không hợp lệ: $target")
            }
            require(host.isNotBlank() && host != "0.0.0.0" && host != "::") {
                "đích Bedrock không hợp lệ: $target"
            }
            require(port in 1..65535) { "đích Bedrock port không hợp lệ: $target" }
        }

        fun javaExecutable(): String {
            val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
            val candidate = Path.of(System.getProperty("java.home"), "bin", exe)
            return if (Files.isRegularFile(candidate)) candidate.toString() else "java"
        }

        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
