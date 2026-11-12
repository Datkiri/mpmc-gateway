package org.mpmc.coordinator.events

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.serialization.json.Json
import org.mpmc.protocol.WsEvent
import java.util.concurrent.ConcurrentHashMap

class EventHub(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    },
) {
    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    fun add(session: DefaultWebSocketServerSession) {
        sessions.add(session)
    }

    fun remove(session: DefaultWebSocketServerSession) {
        sessions.remove(session)
    }

    suspend fun broadcast(event: WsEvent) {
        if (sessions.isEmpty()) return
        val text = json.encodeToString(WsEvent.serializer(), event)
        for (session in sessions) {
            try {
                session.send(Frame.Text(text))
            } catch (_: Exception) {
                sessions.remove(session)
            }
        }
    }
}
