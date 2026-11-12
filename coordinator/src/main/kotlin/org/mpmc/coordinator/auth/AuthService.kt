package org.mpmc.coordinator.auth

import org.mpmc.coordinator.http.ApiException
import org.mpmc.coordinator.metrics.Metrics
import org.mpmc.coordinator.store.Store
import org.mpmc.protocol.B64Url
import org.mpmc.protocol.ChallengeRequest
import org.mpmc.protocol.ChallengeResponse
import org.mpmc.protocol.Digest
import org.mpmc.protocol.Ed25519
import org.mpmc.protocol.EndpointId
import org.mpmc.protocol.TokenRequest
import org.mpmc.protocol.TokenResponse
import java.time.Clock
import java.time.Instant

class AuthService(
    private val store: Store,
    private val keys: SigningKeys,
    private val challenges: ChallengeStore,
    private val clock: Clock,
    private val metrics: Metrics,
    private val accessTokenTtlSeconds: Long = 900,
) {
    suspend fun challenge(request: ChallengeRequest): ChallengeResponse {
        if (!EndpointId.isValid(request.endpointId)) {
            throw ApiException.badRequest("INVALID_ENDPOINT_ID", "endpointId must be 64 lowercase hex characters")
        }
        val clientNonce = runCatching { B64Url.decode(request.clientNonce) }.getOrNull()
            ?: throw ApiException.badRequest("INVALID_NONCE", "clientNonce must be base64url")
        if (clientNonce.size !in 8..64) {
            throw ApiException.badRequest("INVALID_NONCE", "clientNonce must decode to 8..64 bytes")
        }
        val challenge = challenges.create(request.endpointId, request.clientNonce)
        return ChallengeResponse(
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            expiresAt = challenge.expiresAt.toString(),
        )
    }

    suspend fun token(request: TokenRequest): TokenResponse {
        val challenge = challenges.get(request.challengeId) ?: fail("challenge expired or unknown")
        challenges.remove(request.challengeId)

        val signature = runCatching { B64Url.decode(request.signature) }.getOrNull()
            ?: fail("signature must be base64url")
        if (signature.size != 64) fail("signature must be 64 bytes")

        val rawPublicKey = Digest.fromHexOrNull(challenge.endpointId) ?: fail("invalid endpoint id")
        val message = "mpmc-auth-v1${challenge.challengeId}${challenge.nonce}".toByteArray(Charsets.UTF_8)
        if (!Ed25519.verify(rawPublicKey, message, signature)) fail("signature does not verify")

        val now = Instant.now(clock)
        val agent = store.findAgent(challenge.endpointId)
        if (agent?.bannedUntil?.isAfter(now) == true) {
            metrics.incAuthFailure()
            throw ApiException.forbidden("endpoint is banned")
        }

        store.upsertAgent(challenge.endpointId, now)
        val accessToken = keys.issueAccessToken(challenge.endpointId, now, accessTokenTtlSeconds)
        return TokenResponse(
            accessToken = accessToken.value,
            tokenType = "Bearer",
            expiresAt = accessToken.expiresAt.toString(),
        )
    }

    fun authenticateBearer(header: String?): String? {
        if (header == null) return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        val value = header.substring(7).trim()
        if (value.isEmpty()) return null
        return keys.verifyAccessToken(value, Instant.now(clock))
    }

    private fun fail(detail: String): Nothing {
        metrics.incAuthFailure()
        throw ApiException.unauthorized(detail)
    }
}
