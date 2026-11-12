package org.mpmc.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransportSmokeTest {
    @Test
    fun `offline direct dial carries bytes both ways`() = runBlocking {
        val transport = IrohTransport()
        val listener = transport.bind(
            TransportConfig(secretKey = null, relayMode = RelayMode.Disabled, bindAddr = "127.0.0.1:0"),
        )
        val dialer = transport.bind(
            TransportConfig(secretKey = null, relayMode = RelayMode.Disabled, bindAddr = "127.0.0.1:0"),
        )
        try {
            val addresses = listener.boundAddresses()
            assertTrue(addresses.isNotEmpty(), "listener must expose a direct address")

            val hostJob = async(Dispatchers.Default) {
                withTimeout(20_000) {
                    val incoming = listener.incoming().first()
                    val conn = incoming.accept()
                    val stream = conn.acceptStream()
                    val buf = ByteArray(5)
                    var off = 0
                    while (off < buf.size) {
                        val n = stream.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    assertArrayEquals("hello".encodeToByteArray(), buf)
                    stream.writeAll("world".encodeToByteArray())
                    stream.finish()
                    conn.close()
                    "world"
                }
            }

            val conn = dialer.dial(listener.endpointId, directAddresses = addresses)
            val stream = conn.openStream()
            stream.writeAll("hello".encodeToByteArray())
            val reply = ByteArray(5)
            var off = 0
            while (off < reply.size) {
                val n = stream.read(reply, off, reply.size - off)
                if (n < 0) break
                off += n
            }
            assertEquals("world", reply.decodeToString())
            assertEquals("world", withTimeout(10_000) { hostJob.await() })
            assertTrue(conn.kind() == PathKind.DIRECT, "expected DIRECT, got ${conn.kind()}")
            stream.close()
            conn.close()
        } finally {
            dialer.close()
            listener.close()
        }
    }
}
