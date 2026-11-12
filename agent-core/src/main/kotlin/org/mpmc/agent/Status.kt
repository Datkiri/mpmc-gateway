package org.mpmc.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class SessionState(
    val role: String,
    val roomCode: String? = null,
    val endpointId: String,
    val state: String,
    val path: String? = null,
    val rttMs: Long? = null,
    val relayRttMs: Long? = null,
    val setupMs: Long? = null,
    val localAddress: String? = null,
    val players: Int? = null,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val openTcp: Int = 0,
    val error: String? = null,
) {
    companion object {
        const val STATE_DISCOVERING = "discovering"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_RECONNECTING = "reconnecting"
        const val STATE_ERROR = "error"
        const val STATE_IDLE = "idle"
    }
}

class SessionStore(private val dataDir: Path) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
    }
    private val path = dataDir.resolve("session.json")
    private val lock = Any()

    fun write(state: SessionState) {
        val text = json.encodeToString(SessionState.serializer(), state)
        synchronized(lock) {
            runCatching { Files.createDirectories(dataDir) }
            repeat(4) { attempt ->
                val tmp = dataDir.resolve("session.json.${ProcessHandle.current().pid()}.${Thread.currentThread().id}.$attempt.tmp")
                if (runCatching {
                        Files.writeString(tmp, text)
                        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    }.isSuccess
                ) {
                    return
                }
                if (runCatching {
                        Files.writeString(tmp, text)
                        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                    }.isSuccess
                ) {
                    runCatching { Files.deleteIfExists(tmp) }
                    return
                }
                runCatching { Files.deleteIfExists(tmp) }
                Thread.sleep(25)
            }
        }
    }

    fun read(): SessionState? {
        if (!Files.exists(path)) return null
        repeat(2) { attempt ->
            val decoded = runCatching {
                json.decodeFromString(SessionState.serializer(), Files.readString(path))
            }.getOrNull()
            if (decoded != null) return decoded
            if (attempt == 0) Thread.sleep(10)
        }
        return null
    }

    fun clear() {
        runCatching { Files.deleteIfExists(path) }
    }
}
