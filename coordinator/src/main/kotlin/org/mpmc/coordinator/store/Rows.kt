package org.mpmc.coordinator.store

import java.time.Instant
import java.util.UUID

data class AgentRow(
    val endpointId: String,
    val lastSeenAt: Instant?,
    val bannedUntil: Instant?,
)

data class RoomRow(
    val roomId: UUID,
    val code: String,
    val hostEndpointId: String,
    val hostTokenHash: ByteArray,
    val inviteTokenHash: ByteArray,
    val approvalMode: String,
    val maxMembers: Int,
    val state: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastHeartbeat: Instant,
)

data class MemberRow(
    val roomId: UUID,
    val endpointId: String,
    val role: String,
    val joinedAt: Instant,
    val lastSeen: Instant,
)

data class JoinRequestRow(
    val requestId: UUID,
    val roomId: UUID,
    val endpointId: String,
    val state: String,
    val reason: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val approvedAt: Instant?,
)

data class TicketRow(
    val jti: UUID,
    val roomId: UUID,
    val endpointId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val maxStreams: Int,
)
