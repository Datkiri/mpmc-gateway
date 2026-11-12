package org.mpmc.coordinator.metrics

import org.mpmc.protocol.JoinState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

class Metrics {
    private data class Series(val name: String, val labels: Map<String, String>)

    private val counters = ConcurrentHashMap<Series, LongAdder>()
    private val roomsActive = AtomicInteger(0)

    fun inc(name: String, labels: Map<String, String> = emptyMap(), delta: Long = 1) {
        counters.computeIfAbsent(Series(name, labels)) { LongAdder() }.add(delta)
    }

    fun incJoin(state: String, delta: Long = 1) =
        inc("mpmc_join_requests_total", mapOf("state" to state), delta)

    fun incAuthFailure() = inc("mpmc_auth_failures_total")

    fun incRelay(result: String) = inc("mpmc_relay_auth_total", mapOf("result" to result))

    fun incTicketIssued() = inc("mpmc_ticket_issued_total")

    fun observeHttp(route: String, status: Int) =
        inc("mpmc_http_requests_total", mapOf("route" to route, "status" to status.toString()))

    fun setRoomsActive(value: Int) = roomsActive.set(value.coerceAtLeast(0))

    fun render(): String = buildString {
        append("# HELP mpmc_rooms_active Rooms currently active\n")
        append("# TYPE mpmc_rooms_active gauge\n")
        append("mpmc_rooms_active ").append(roomsActive.get()).append('\n')

        val grouped = counters.entries.groupBy { it.key.name }
        for ((name, entries) in grouped.toSortedMap()) {
            append("# TYPE ").append(name).append(" counter\n")
            for ((series, adder) in entries.sortedBy { it.key.labels.toSortedMap().toString() }) {
                append(name)
                if (series.labels.isNotEmpty()) {
                    append('{')
                    append(
                        series.labels.toSortedMap().entries.joinToString(",") { (key, value) ->
                            "$key=\"${escape(value)}\""
                        },
                    )
                    append('}')
                }
                append(' ').append(adder.sum()).append('\n')
            }
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    companion object {
        val JOIN_STATES = listOf(JoinState.PENDING, JoinState.APPROVED, JoinState.REJECTED, JoinState.EXPIRED)
    }
}
