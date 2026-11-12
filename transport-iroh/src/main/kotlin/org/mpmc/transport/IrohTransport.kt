package org.mpmc.transport

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointId
import computer.iroh.IrohException
import computer.iroh.PathChangeCallback
import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

class IrohTransport : Transport {
    override fun close() = Unit

    override suspend fun bind(cfg: TransportConfig): Peer {
        val builder = EndpointBuilder()
        try {
            when (val mode = cfg.relayMode) {
                RelayMode.N0 -> builder.applyN0()
                RelayMode.Disabled -> {
                    builder.applyMinimal()
                    builder.relayMode(computer.iroh.RelayMode.disabled())
                }
                is RelayMode.Custom -> {
                    builder.applyMinimal()
                    builder.relayMode(computer.iroh.RelayMode.customFromUrls(mode.urls))
                }
            }
            cfg.secretKey?.let { builder.secretKey(it) }
            cfg.bindAddr?.let { builder.bindAddr(it) }
            builder.alpns(listOf(cfg.alpn.encodeToByteArray()))
            return IrohPeer(builder.bind(), cfg)
        } catch (e: IrohException) {
            throw TransportException("iroh bind failed: ${e.message()}", e)
        } finally {
            runCatching { builder.close() }
        }
    }
}

private class IrohPeer(
    private val endpoint: Endpoint,
    private val cfg: TransportConfig,
) : Peer {
    private val log = LoggerFactory.getLogger(IrohPeer::class.java)
    private val alpnBytes = cfg.alpn.encodeToByteArray()
    private val relayHint = (cfg.relayMode as? RelayMode.Custom)?.urls?.firstOrNull()

    override val endpointId: String = endpoint.id().toString()

    override suspend fun dial(
        remoteEndpointId: String,
        directAddresses: List<String>,
        relayUrl: String?,
    ): PeerConnection {
        val remote = try {
            EndpointId.fromString(remoteEndpointId)
        } catch (e: IrohException) {
            throw TransportException("invalid endpoint id", e)
        }
        val addr = EndpointAddr(remote, relayUrl ?: relayHint, directAddresses)
        return try {
            withTimeout(DIAL_TIMEOUT_MS) {
                IrohConnection(endpoint.connect(addr, alpnBytes))
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw TransportException("iroh dial timeout", e)
        } catch (e: IrohException) {
            throw TransportException("iroh dial failed: ${e.message()}", e)
        }
    }

    override fun incoming(): Flow<Incoming> = channelFlow {
        while (true) {
            val next = try {
                endpoint.acceptNext()
            } catch (e: Exception) {
                break
            } ?: break
            send(IrohIncoming(next))
        }
    }

    override fun boundAddresses(): List<String> =
        endpoint.boundSockets().filterNot { it.startsWith("0.0.0.0") || it.startsWith("[::]") }

    override suspend fun updateRelay(urls: List<String>) {
        log.warn("update_relay_unsupported count=${urls.size}")
    }

    override fun close() {
        runBlocking {
            runCatching { withTimeout(5_000) { endpoint.shutdown() } }
        }
        runCatching { endpoint.close() }
    }

    companion object {
        private const val DIAL_TIMEOUT_MS = 20_000L
    }
}

private class IrohIncoming(
    private val incoming: computer.iroh.Incoming,
) : Incoming {
    override val remoteId: String get() = "unknown"

    override suspend fun accept(): PeerConnection =
        IrohConnection(incoming.accept().connect())

    override suspend fun refuse() {
        incoming.refuse()
    }
}

private class IrohConnection(
    private val conn: Connection,
) : PeerConnection {
    override val remoteId: String = conn.remoteId().toString()

    override suspend fun openStream(): DuplexStream = IrohDuplex(conn.openBi())

    override suspend fun acceptStream(): DuplexStream = IrohDuplex(conn.acceptBi())

    override suspend fun paths(): List<PathSnapshot> = conn.paths().map { p ->
        PathSnapshot(
            isIp = p.isIp,
            isRelay = p.isRelay,
            isSelected = p.isSelected,
            rttMs = p.rttMs.toLong(),
            remoteAddr = p.remoteAddr,
        )
    }

    override fun pathEvents(): Flow<PathSnapshot> = channelFlow {
        val handle = conn.watchPaths(
            object : PathChangeCallback {
                override suspend fun onChange(paths: List<computer.iroh.PathSnapshot>) {
                    paths.forEach { p ->
                        send(
                            PathSnapshot(
                                isIp = p.isIp,
                                isRelay = p.isRelay,
                                isSelected = p.isSelected,
                                rttMs = p.rttMs.toLong(),
                                remoteAddr = p.remoteAddr,
                            ),
                        )
                    }
                }
            },
        )
        awaitClose { runBlocking { runCatching { handle.stop() } } }
    }

    override suspend fun closedReason(): String? =
        runCatching { conn.closed() }.getOrDefault("closed")

    override fun close() {
        runCatching { conn.close(0L, byteArrayOf()) }
        runCatching { conn.close() }
    }
}

private class IrohDuplex(
    private val bi: BiStream,
) : DuplexStream {
    private val send: SendStream = bi.send()
    private val recv: RecvStream = bi.recv()

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        val chunk = recv.read(len.toUInt()) ?: return -1
        if (chunk.isEmpty()) return -1
        chunk.copyInto(buf, off)
        return chunk.size
    }

    override suspend fun writeAll(buf: ByteArray, off: Int, len: Int) {
        val slice = if (off == 0 && len == buf.size) buf else buf.copyOfRange(off, off + len)
        send.writeAll(slice)
    }

    override suspend fun finish() {
        send.finish()
    }

    override suspend fun reset(code: Long) {
        send.reset(code.toULong())
    }

    override fun close() {
        runCatching { send.close() }
        runCatching { recv.close() }
        runCatching { bi.close() }
    }
}
