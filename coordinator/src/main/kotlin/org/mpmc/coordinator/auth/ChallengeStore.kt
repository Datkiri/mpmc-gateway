package org.mpmc.coordinator.auth

import org.mpmc.protocol.Tokens
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChallengeStore(
    private val clock: Clock = Clock.systemUTC(),
    private val ttlSeconds: Long = 60,
    private val maxEntries: Int = 100_000,
) {
    data class Challenge(
        val challengeId: String,
        val endpointId: String,
        val nonce: String,
        val clientNonce: String,
        val expiresAt: Instant,
    )

    private val challenges = ConcurrentHashMap<String, Challenge>()

    fun create(endpointId: String, clientNonce: String): Challenge {
        if (challenges.size >= maxEntries) purgeExpired()
        if (challenges.size >= maxEntries) challenges.remove(challenges.keys.first())
        val challenge = Challenge(
            challengeId = UUID.randomUUID().toString(),
            endpointId = endpointId,
            nonce = Tokens.randomB64Url(32),
            clientNonce = clientNonce,
            expiresAt = Instant.now(clock).plusSeconds(ttlSeconds),
        )
        challenges[challenge.challengeId] = challenge
        return challenge
    }

    fun get(challengeId: String): Challenge? {
        val challenge = challenges[challengeId] ?: return null
        return challenge.takeIf { it.expiresAt.isAfter(Instant.now(clock)) }
    }

    fun remove(challengeId: String) {
        challenges.remove(challengeId)
    }

    fun purgeExpired(): Int {
        val now = Instant.now(clock)
        val before = challenges.size
        challenges.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        return before - challenges.size
    }
}
