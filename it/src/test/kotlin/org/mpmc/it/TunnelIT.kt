package org.mpmc.it

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mpmc.agent.SessionState
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class TunnelIT {

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `echo one mebibyte through tunnel`() = runBlocking {
        TunnelHarness().use { harness ->
            harness.start(this)
            harness.connect().use { socket ->
                val payload = ByteArray(1024 * 1024) { (it * 31).toByte() }
                val writer = async(Dispatchers.IO) {
                    val out = socket.getOutputStream()
                    var offset = 0
                    while (offset < payload.size) {
                        val chunk = minOf(16 * 1024, payload.size - offset)
                        out.write(payload, offset, chunk)
                        offset += chunk
                    }
                    out.flush()
                }
                val received = socket.readFully(payload.size)
                writer.await()
                assertArrayEqualsSha256(payload, received)
            }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `half close propagates fin and returns tail`() = runBlocking {
        TunnelHarness().use { harness ->
            harness.start(this)
            harness.connect().use { socket ->
                socket.getOutputStream().apply {
                    write("hello".toByteArray())
                    flush()
                }
                socket.shutdownOutput()
                val response = socket.getInputStream().readBytes()
                assertEquals("helloTAIL", String(response))
            }
        }
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `five concurrent tcp connections share one quic connection`() = runBlocking {
        TunnelHarness().use { harness ->
            harness.start(this)
            val jobs = (1..5).map { index ->
                async(Dispatchers.IO) {
                    harness.connect().use { socket ->
                        val payload = ByteArray(64 * 1024) { ((it + index) * 17).toByte() }
                        val writer = async(Dispatchers.IO) {
                            socket.getOutputStream().apply {
                                write(payload)
                                flush()
                            }
                        }
                        val received = socket.readFully(payload.size)
                        writer.await()
                        assertArrayEqualsSha256(payload, received)
                        index
                    }
                }
            }
            assertEquals(listOf(1, 2, 3, 4, 5), jobs.awaitAll())
        }
    }

    @Test
    @Timeout(180, unit = TimeUnit.SECONDS)
    fun `host loss moves join to reconnecting and recovers after restart`() = runBlocking {
        val hostPort = freePort()
        TunnelHarness(hostBind = "127.0.0.1:$hostPort").use { harness ->
            harness.start(this)
            harness.connect().use { socket ->
                socket.getOutputStream().apply {
                    write("before".toByteArray())
                    flush()
                }
                assertEquals("before", String(socket.readFully(6)))
            }

            harness.hostService.close()
            val reconnecting = waitForState(harness.joinStatus, SessionState.STATE_RECONNECTING, 30_000)
            assertNotNull(reconnecting, "join phải chuyển sang reconnecting; session=${harness.joinStatus.read()}")
            assertEquals(harness.proxyAddress, harness.joinStatus.read()?.localAddress)

            harness.restartHost()
            val connected = waitForState(harness.joinStatus, SessionState.STATE_CONNECTED, 60_000)
            assertNotNull(connected, "join phải kết nối lại; session=${harness.joinStatus.read()}")

            harness.connect().use { socket ->
                socket.getOutputStream().apply {
                    write("after".toByteArray())
                    flush()
                }
                assertEquals("after", String(socket.readFully(5)))
            }
        }
    }

    @Test
    @Timeout(180, unit = TimeUnit.SECONDS)
    fun `one hundred sequential connections do not leak sockets`() = runBlocking {
        TunnelHarness().use { harness ->
            harness.start(this)
            repeat(100) { index ->
                harness.connect().use { socket ->
                    val payload = "conn-$index".toByteArray()
                    socket.getOutputStream().apply {
                        write(payload)
                        flush()
                    }
                    assertEquals(String(payload), String(socket.readFully(payload.size)))
                }
            }
            assertTrue(
                waitUntil(15_000) { harness.joinStatus.read()?.openTcp == 0 },
                "socket cục bộ còn mở: ${harness.joinStatus.read()?.openTcp}",
            )
            assertEquals(0, harness.joinStatus.read()?.openTcp)
            harness.connect().use { socket ->
                socket.getOutputStream().apply {
                    write("final".toByteArray())
                    flush()
                }
                assertEquals("final", String(socket.readFully(5)))
            }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `status reports direct path in disabled relay mode`() = runBlocking {
        TunnelHarness().use { harness ->
            harness.start(this)
            val state = harness.joinStatus.read()
            assertEquals(SessionState.STATE_CONNECTED, state?.state)
            assertEquals("direct", state?.path, "session=$state")
            assertEquals("host", harness.hostStatus.read()?.role)
            assertTrue((state?.setupMs ?: Long.MAX_VALUE) >= 0)
        }
    }

    private fun assertArrayEqualsSha256(expected: ByteArray, actual: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-256")
        assertTrue(expected.size == actual.size, "kích thước ${actual.size} != ${expected.size}")
        assertTrue(
            digest.digest(expected).contentEquals(digest.digest(actual)),
            "nội dung khác nhau",
        )
    }
}
