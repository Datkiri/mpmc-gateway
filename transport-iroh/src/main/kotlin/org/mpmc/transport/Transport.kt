package org.mpmc.transport

import kotlinx.coroutines.flow.Flow

sealed interface RelayMode {
    data object N0 : RelayMode
    data object Disabled : RelayMode
    data class Custom(val urls: List<String>) : RelayMode
}

data class TransportConfig(
    val secretKey: ByteArray?,
    val alpn: String = "mpmc/1",
    val relayMode: RelayMode,
    val bindAddr: String? = null,
)

data class PathSnapshot(
    val isIp: Boolean,
    val isRelay: Boolean,
    val isSelected: Boolean,
    val rttMs: Long?,
    val remoteAddr: String?,
)

enum class PathKind { DIRECT, RELAY, MIXED, NONE }

fun classifyPath(paths: List<PathSnapshot>): PathKind = when {
    paths.isEmpty() -> PathKind.NONE
    paths.all { it.isRelay } -> PathKind.RELAY
    paths.none { it.isRelay } -> PathKind.DIRECT
    else -> PathKind.MIXED
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface Transport : AutoCloseable {
    suspend fun bind(cfg: TransportConfig): Peer
}

interface Peer : AutoCloseable {
    val endpointId: String

    /**
     * [directAddresses] và [relayUrl] chỉ dùng cho test offline / relay custom chưa có discovery.
     * Production dial id-only với danh sách rỗng.
     */
    suspend fun dial(
        remoteEndpointId: String,
        directAddresses: List<String> = emptyList(),
        relayUrl: String? = null,
    ): PeerConnection

    fun incoming(): Flow<Incoming>
    fun boundAddresses(): List<String>
    suspend fun updateRelay(urls: List<String>)
    override fun close()
}

interface Incoming {
    val remoteId: String
    suspend fun accept(): PeerConnection
    suspend fun refuse()
}

interface PeerConnection : AutoCloseable {
    val remoteId: String
    suspend fun openStream(): DuplexStream
    suspend fun acceptStream(): DuplexStream
    suspend fun paths(): List<PathSnapshot>
    fun pathEvents(): Flow<PathSnapshot>
    suspend fun kind(): PathKind = classifyPath(paths())

    /** Trả về khi connection đã đóng; null nếu binding không hỗ trợ. */
    suspend fun closedReason(): String?

    override fun close()
}

interface DuplexStream : AutoCloseable {
    /** -1 = EOF. */
    suspend fun read(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int
    suspend fun writeAll(buf: ByteArray, off: Int = 0, len: Int = buf.size - off)
    suspend fun finish()
    suspend fun reset(code: Long)
    override fun close()
}
