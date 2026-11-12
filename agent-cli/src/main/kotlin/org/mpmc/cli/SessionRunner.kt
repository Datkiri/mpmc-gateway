package org.mpmc.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.mpmc.agent.SessionState
import org.mpmc.agent.SessionStore

internal object SessionJson {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(state: SessionState): String = json.encodeToString(SessionState.serializer(), state)
}

/**
 * Chạy service ở foreground, mở cổng điều khiển cục bộ để `mpmc disconnect` dừng phiên.
 */
internal fun runTunnel(
    status: SessionStore,
    service: AutoCloseable,
    run: suspend () -> Unit,
    controlPort: Int = ControlServer.DEFAULT_PORT,
): Int = runBlocking {
    val stop = CompletableDeferred<Unit>()
    val control = ControlServer(controlPort) { command ->
        when (command) {
            "stop" -> {
                stop.complete(Unit)
                "OK stopping"
            }
            "status" -> status.read()?.let(SessionJson::encode) ?: "ERR no-session"
            "ping" -> "OK pong"
            else -> "ERR unknown"
        }
    }
    if (!control.start()) {
        println("Cảnh báo: cổng điều khiển 127.0.0.1:$controlPort đang bận; lệnh 'disconnect' sẽ không hoạt động.")
    }
    val job = launch(Dispatchers.Default) { run() }
    val stopJob = launch { stop.await(); job.cancelAndJoin() }
    job.join()
    stopJob.cancel()
    control.close()
    runCatching { service.close() }
    val state = status.read()
    if (state?.state == SessionState.STATE_ERROR) 2 else 0
}
