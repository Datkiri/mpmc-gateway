package org.mpmc.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object ApprovalMode {
    const val PROMPT = "prompt"
    const val INVITE = "invite"
    fun isValid(value: String): Boolean = value == PROMPT || value == INVITE
}

object RoomState {
    const val ACTIVE = "active"
    const val CLOSED = "closed"
    const val EXPIRED = "expired"
}

object JoinState {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"
}

object MemberRole {
    const val HOST = "host"
    const val GUEST = "guest"
}

@Serializable
data class ChallengeRequest(val endpointId: String, val clientNonce: String)

@Serializable
data class ChallengeResponse(val challengeId: String, val nonce: String, val expiresAt: String)

@Serializable
data class TokenRequest(val challengeId: String, val signature: String)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: String,
)

@Serializable
data class CreateRoomRequest(
    val maxMembers: Int = 8,
    val approvalMode: String = ApprovalMode.PROMPT,
    val ttlMinutes: Int = 120,
    val label: String? = null,
)

@Serializable
data class CreateRoomResponse(
    val roomId: String,
    val code: String,
    val inviteToken: String,
    val hostToken: String,
    val expiresAt: String,
    val relayUrls: List<String> = emptyList(),
)

@Serializable
data class RoomInfoResponse(
    val roomId: String,
    val hostEndpointId: String,
    val memberCount: Int,
    val state: String,
    val approvalMode: String,
    val expiresAt: String,
)

@Serializable
data class KickRequest(val endpointId: String)

@Serializable
data class JoinRoomRequest(val inviteToken: String? = null, val message: String? = null)

@Serializable
data class JoinRoomResponse(val joinRequestId: String, val state: String, val ticket: String? = null)

@Serializable
data class JoinStatusResponse(val state: String, val ticket: String? = null, val reason: String? = null)

@Serializable
data class ApproveResponse(val ticket: String)

@Serializable
data class RejectRequest(val reason: String? = null)

@Serializable
data class ProblemResponse(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val code: String,
)

@Serializable
data class JwkKey(val kty: String, val crv: String, val kid: String, val x: String)

@Serializable
data class JwksResponse(val keys: List<JwkKey>)

@Serializable
data class WsEvent(
    val type: String,
    val roomId: String? = null,
    val ts: String,
    val data: JsonObject = JsonObject(emptyMap()),
)
