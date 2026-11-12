package org.mpmc.cli

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Điều khiển tiến trình host/join đang chạy trong nền từ một lệnh CLI khác.
 * Chỉ bind 127.0.0.1 và không nhận dữ liệu nhạy cảm.
 */
class ControlServer(
    private val port: Int = DEFAULT_PORT,
    private val onCommand: (String) -> String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(ControlServer::class.java)
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start(): Boolean {
        server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
            }
        }.getOrNull() ?: return false
        running = true
        worker = thread(name = "mpmc-control", isDaemon = true) {
            while (running) {
                val socket = try {
                    server?.accept() ?: break
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    socket.use { handle(it) }
                }
            }
        }
        log.info("control_listening port=$port")
        return true
    }

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val command = reader.readLine()?.trim()?.lowercase().orEmpty()
            val response = if (command.isEmpty()) "ERR empty" else onCommand(command)
            writer.write(response)
            writer.newLine()
            writer.flush()
        }
    }

    override fun close() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    companion object {
        const val DEFAULT_PORT = 45123

        fun request(command: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 3_000): String? =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    writer.write(command)
                    writer.newLine()
                    writer.flush()
                    BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                }
            }.getOrNull()
    }
}
