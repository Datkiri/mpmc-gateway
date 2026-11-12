package org.mpmc.coordinator.log

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.encoder.EncoderBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class JsonLogEncoder : EncoderBase<ILoggingEvent>() {
    private val json = Json { encodeDefaults = true }

    override fun headerBytes(): ByteArray? = null

    override fun footerBytes(): ByteArray? = null

    override fun encode(event: ILoggingEvent): ByteArray {
        val entry = buildJsonObject {
            put("ts", Instant.ofEpochMilli(event.timeStamp).toString())
            put("level", event.level.levelStr)
            put("logger", event.loggerName)
            put("thread", event.threadName)
            put("msg", event.formattedMessage)
            event.throwableProxy?.let { proxy ->
                put("error", proxy.className + (proxy.message?.let { ": $it" } ?: ""))
            }
        }
        return (json.encodeToString(JsonObject.serializer(), entry) + "\n").toByteArray(Charsets.UTF_8)
    }
}
