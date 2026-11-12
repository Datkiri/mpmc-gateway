package org.mpmc.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.mpmc.transport.DuplexStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class Meter {
    private val up = AtomicLong()
    private val down = AtomicLong()

    fun addUp(bytes: Int) {
        addUp(bytes.toLong())
    }

    fun addUp(bytes: Long) {
        up.addAndGet(bytes)
    }

    fun addDown(bytes: Int) {
        addDown(bytes.toLong())
    }

    fun addDown(bytes: Long) {
        down.addAndGet(bytes)
    }

    val bytesUp: Long get() = up.get()
    val bytesDown: Long get() = down.get()
}

object TunnelBridge {
    private const val BUFFER_BYTES = 16 * 1024

    suspend fun splice(socket: Socket, stream: DuplexStream, meter: Meter) = coroutineScope {
        socket.tcpNoDelay = true

        val up = launch(Dispatchers.IO) {
            val buf = ByteArray(BUFFER_BYTES)
            try {
                while (true) {
                    val n = socket.getInputStream().read(buf)
                    if (n < 0) {
                        stream.finish()
                        break
                    }
                    stream.writeAll(buf, 0, n)
                    meter.addUp(n)
                }
            } catch (e: IOException) {
                runCatching { stream.reset(1) }
                throw e
            }
        }

        val down = launch(Dispatchers.IO) {
            val buf = ByteArray(BUFFER_BYTES)
            try {
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) {
                        runCatching { socket.shutdownOutput() }
                        break
                    }
                    socket.getOutputStream().write(buf, 0, n)
                    socket.getOutputStream().flush()
                    meter.addDown(n)
                }
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

        try {
            joinAll(up, down)
        } finally {
            runCatching { socket.close() }
            runCatching { stream.close() }
        }
    }
}
