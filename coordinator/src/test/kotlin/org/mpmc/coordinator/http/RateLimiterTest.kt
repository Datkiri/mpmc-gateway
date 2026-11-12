package org.mpmc.coordinator.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mpmc.protocol.Tokens
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RateLimiterTest {
    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val limiter = RateLimiter(clock)
    private val endpointId = Tokens.randomHex(32)

    @Test
    fun `join allows five per minute then resets`() {
        repeat(5) {
            assertEquals(RateLimiter.Decision.Allowed, limiter.checkJoin("10.0.0.1", endpointId))
        }
        val limited = assertInstanceOf(RateLimiter.Decision.Limited::class.java, limiter.checkJoin("10.0.0.1", endpointId))
        assertTrue(limited.retryAfterSeconds in 1..60)

        clock.advanceSeconds(61)
        assertEquals(RateLimiter.Decision.Allowed, limiter.checkJoin("10.0.0.1", endpointId))
    }

    @Test
    fun `join allows thirty per hour`() {
        repeat(30) {
            assertEquals(RateLimiter.Decision.Allowed, limiter.checkJoin("10.0.0.2", Tokens.randomHex(32)))
            clock.advanceSeconds(61)
        }
        assertInstanceOf(RateLimiter.Decision.Limited::class.java, limiter.checkJoin("10.0.0.2", Tokens.randomHex(32)))
    }

    @Test
    fun `create allows five per hour per ip`() {
        repeat(5) {
            assertEquals(RateLimiter.Decision.Allowed, limiter.checkCreate("10.0.0.3", endpointId))
        }
        assertInstanceOf(RateLimiter.Decision.Limited::class.java, limiter.checkCreate("10.0.0.3", endpointId))
    }

    @Test
    fun `any request allows one hundred twenty per minute`() {
        repeat(120) {
            assertEquals(RateLimiter.Decision.Allowed, limiter.checkAnyRequest(endpointId))
        }
        assertInstanceOf(RateLimiter.Decision.Limited::class.java, limiter.checkAnyRequest(endpointId))
    }

    @Test
    fun `twenty bad codes block joins for an hour`() {
        repeat(20) { limiter.recordBadCode("10.0.0.4") }

        val blocked = assertInstanceOf(
            RateLimiter.Decision.Limited::class.java,
            limiter.checkJoin("10.0.0.4", endpointId),
        )
        assertTrue(blocked.retryAfterSeconds > 3500)

        clock.advanceSeconds(3601)
        assertEquals(RateLimiter.Decision.Allowed, limiter.checkJoin("10.0.0.4", endpointId))
    }

    @Test
    fun `purge removes stale windows`() {
        limiter.checkJoin("10.0.0.5", endpointId)
        clock.advanceSeconds(3601)
        limiter.purge()
        repeat(5) {
            assertEquals(RateLimiter.Decision.Allowed, limiter.checkJoin("10.0.0.5", endpointId))
        }
    }
}
