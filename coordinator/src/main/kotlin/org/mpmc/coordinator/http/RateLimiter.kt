package org.mpmc.coordinator.http

import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class RateLimiter(private val clock: Clock = Clock.systemUTC()) {
    sealed interface Decision {
        data object Allowed : Decision
        data class Limited(val retryAfterSeconds: Long) : Decision
    }

    private sealed interface Hit {
        data class Accepted(val count: Int) : Hit
        data class Rejected(val retryAfterSeconds: Long) : Hit
    }

    private val windows = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val blockedUntil = ConcurrentHashMap<String, Instant>()

    fun checkJoin(ip: String, endpointId: String): Decision {
        checkBlocked(joinBlockKey(ip))?.let { return it }
        limited(consume("join-ip-min:$ip", 5, 60))?.let { return it }
        limited(consume("join-ip-hour:$ip", 30, 3600))?.let { return it }
        limited(consume("join-endpoint-hour:$endpointId", 20, 3600))?.let { return it }
        return Decision.Allowed
    }

    fun checkCreate(ip: String, endpointId: String): Decision {
        limited(consume("create-ip-hour:$ip", 5, 3600))?.let { return it }
        limited(consume("create-endpoint-hour:$endpointId", 5, 3600))?.let { return it }
        return Decision.Allowed
    }

    fun checkAnyRequest(endpointId: String): Decision {
        limited(consume("any-endpoint-min:$endpointId", 120, 60))?.let { return it }
        return Decision.Allowed
    }

    fun recordBadCode(ip: String) {
        val hit = consume("badcode-ip-hour:$ip", 20, 3600)
        if (hit is Hit.Accepted && hit.count >= 20) {
            blockedUntil[joinBlockKey(ip)] = Instant.now(clock).plusSeconds(3600)
        }
    }

    fun purge() {
        val cutoff = clock.millis() - 3600_000
        windows.entries.removeIf { (_, window) ->
            synchronized(window) { window.isEmpty() || window.last() < cutoff }
        }
        val now = Instant.now(clock)
        blockedUntil.entries.removeIf { it.value.isBefore(now) }
    }

    private fun limited(hit: Hit): Decision.Limited? =
        (hit as? Hit.Rejected)?.let { Decision.Limited(it.retryAfterSeconds) }

    private fun checkBlocked(key: String): Decision.Limited? {
        val until = blockedUntil[key] ?: return null
        val seconds = until.epochSecond - Instant.now(clock).epochSecond
        if (seconds <= 0) {
            blockedUntil.remove(key)
            return null
        }
        return Decision.Limited(seconds.coerceAtLeast(1))
    }

    private fun consume(key: String, limit: Int, windowSeconds: Long): Hit {
        val now = clock.millis()
        val windowMs = windowSeconds * 1000
        val window = windows.computeIfAbsent(key) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && window.first() <= now - windowMs) window.removeFirst()
            if (window.size >= limit) {
                val retryAfter = ((window.first() + windowMs - now) / 1000).coerceAtLeast(1)
                return Hit.Rejected(retryAfter)
            }
            window.addLast(now)
            return Hit.Accepted(window.size)
        }
    }

    private fun joinBlockKey(ip: String): String = "join-block:$ip"
}
