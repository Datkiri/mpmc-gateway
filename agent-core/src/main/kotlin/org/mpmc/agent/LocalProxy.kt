package org.mpmc.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LocalProxy(
    listen: String,
    private val backlog: Int = 16,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(LocalProxy::class.java)
    private val host: String
    private val requestedPort: Int
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val accepted = AtomicInteger()
    private var server: ServerSocket? = null
    private var handlerJobs: MutableList<kotlinx.coroutines.Job> = mutableListOf()

    lateinit var localAddress: String
        private set

    init {
        val idx = listen.lastIndexOf(':')
        require(idx > 0) { "listen phải có dạng host:port: $listen" }
        host = listen.substring(0, idx)
        require(host == "127.0.0.1") { "join.listen chỉ được bind 127.0.0.1, nhận: $host" }
        requestedPort = listen.substring(idx + 1).toIntOrNull()
            ?: throw IllegalArgumentException("port không hợp lệ: $listen")
    }

    fun bind() {
        val address = InetAddress.getByName("127.0.0.1")
        if (requestedPort != 0) {
            server = open(address, requestedPort)
        } else {
            var bound: ServerSocket? = null
            for (port in 25566..25600) {
                bound = runCatching { open(address, port) }.getOrNull()
                if (bound != null) break
            }
            server = bound ?: throw IOException("không còn cổng trống trong 25566..25600")
        }
        localAddress = "127.0.0.1:${server!!.localPort}"
        log.info("local_proxy_bound address=$localAddress")
    }

    val openConnections: Int get() = sockets.size
    val acceptedTotal: Int get() = accepted.get()

    fun closeSockets() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    suspend fun acceptLoop(
        scope: CoroutineScope,
        handler: suspend (Socket) -> Unit,
    ) {
        val serverSocket = server ?: error("bind() chưa được gọi")
        while (scope.isActive) {
            val socket = try {
                runInterruptible(Dispatchers.IO) { serverSocket.accept() }
            } catch (e: Exception) {
                break
            }
            accepted.incrementAndGet()
            sockets.add(socket)
            val job = scope.launch(Dispatchers.IO) {
                try {
                    socket.tcpNoDelay = true
                    handler(socket)
                } catch (_: Exception) {
                    // lỗi kết nối cục bộ được xử lý bằng RST/đóng socket bên dưới
                } finally {
                    runCatching { socket.close() }
                    sockets.remove(socket)
                }
            }
            synchronized(handlerJobs) { handlerJobs.add(job) }
        }
    }

    override fun close() {
        runCatching { server?.close() }
        closeSockets()
        synchronized(handlerJobs) {
            handlerJobs.forEach { runCatching { it.cancel() } }
            handlerJobs.clear()
        }
    }

    private fun open(address: InetAddress, port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, port), backlog)
        }
}
