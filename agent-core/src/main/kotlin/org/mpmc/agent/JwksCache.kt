package org.mpmc.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mpmc.protocol.B64Url
import org.mpmc.protocol.Ed25519
import org.mpmc.protocol.TicketCodec
import org.mpmc.protocol.TicketResult
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

@Serializable
private data class JwksCacheFile(
    val fetchedAt: String,
    val keys: List<String>,
)

/**
 * Cache khóa công khai của Coordinator. Verify ticket là offline, chỉ refresh theo TTL (mặc định 10 phút).
 */
class JwksCache(
    private val fetch: suspend () -> List<ByteArray>,
    private val cacheFile: Path?,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Long = 600,
) {
    private val log = LoggerFactory.getLogger(JwksCache::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    @Volatile
    private var keys: List<ByteArray> = emptyList()

    @Volatile
    private var fetchedAt: Instant = Instant.EPOCH

    fun cachedKeys(): List<ByteArray> = keys

    fun isStale(): Boolean = clock.instant().isAfter(fetchedAt.plusSeconds(ttl))

    suspend fun preload() {
        loadFromDisk()
        if (keys.isEmpty()) refresh(force = true)
    }

    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            if (!force && keys.isNotEmpty() && !isStale()) return
            runCatching { fetch() }
                .onSuccess { fresh ->
                    if (fresh.isNotEmpty()) {
                        keys = fresh
                        fetchedAt = clock.instant()
                        persist()
                        log.info("jwks_refreshed count=${fresh.size}")
                    }
                }
                .onFailure { log.warn("jwks_refresh_failed type=${it::class.simpleName}") }
        }
    }

    fun verify(
        ticket: String,
        nowEpochSeconds: Long,
        expectedRoomId: String,
        expectedEndpointId: String,
    ): TicketResult {
        val available = keys
        if (available.isEmpty()) return TicketResult.Invalid(org.mpmc.protocol.TicketFailure.MALFORMED)
        var last: TicketResult = TicketResult.Invalid(org.mpmc.protocol.TicketFailure.BAD_SIGNATURE)
        for (key in available) {
            val result = TicketCodec.verify(
                ticket = ticket,
                verifySignature = { message, signature -> Ed25519.verify(key, message, signature) },
                nowEpochSeconds = nowEpochSeconds,
                expectedRoomId = expectedRoomId,
                expectedEndpointId = expectedEndpointId,
            )
            if (result is TicketResult.Valid) return result
            last = result
        }
        return last
    }

    private fun loadFromDisk() {
        val file = cacheFile ?: return
        if (!Files.exists(file)) return
        runCatching {
            val parsed = json.decodeFromString(JwksCacheFile.serializer(), Files.readString(file))
            val loaded = parsed.keys.map(B64Url::decode)
            val at = Instant.parse(parsed.fetchedAt)
            if (loaded.isNotEmpty()) {
                keys = loaded
                fetchedAt = at
            }
        }.onFailure { log.warn("jwks_cache_read_failed") }
    }

    private fun persist() {
        val file = cacheFile ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val data = JwksCacheFile(
                fetchedAt = fetchedAt.toString(),
                keys = keys.map(B64Url::encode),
            )
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(JwksCacheFile.serializer(), data))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { log.warn("jwks_cache_write_failed") }
    }
}

class JtiTracker(
    private val nowSeconds: () -> Long,
    private val ttlSeconds: Long = 600,
) {
    private data class Usage(var count: Int, var expiresAt: Long)

    private val usage = HashMap<String, Usage>()

    fun tryConsume(jti: String, maxStreams: Int): Boolean = synchronized(usage) {
        val now = nowSeconds()
        usage.entries.removeIf { it.value.expiresAt <= now }
        val current = usage[jti]
        if (current == null) {
            usage[jti] = Usage(count = 1, expiresAt = now + ttlSeconds)
            return true
        }
        if (current.count >= maxStreams) return false
        current.count += 1
        return true
    }
}
