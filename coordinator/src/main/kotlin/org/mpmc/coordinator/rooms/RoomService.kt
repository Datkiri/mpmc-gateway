package org.mpmc.coordinator.rooms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mpmc.coordinator.auth.SigningKeys
import org.mpmc.coordinator.config.CoordinatorConfig
import org.mpmc.coordinator.events.EventHub
import org.mpmc.coordinator.http.ApiException
import org.mpmc.coordinator.http.RateLimiter
import org.mpmc.coordinator.metrics.Metrics
import org.mpmc.coordinator.store.JoinRequestRow
import org.mpmc.coordinator.store.RoomRow
import org.mpmc.coordinator.store.Store
import org.mpmc.coordinator.store.TicketRow
import org.mpmc.protocol.ApprovalMode
import org.mpmc.protocol.ApproveResponse
import org.mpmc.protocol.ConstantTime
import org.mpmc.protocol.CreateRoomRequest
import org.mpmc.protocol.CreateRoomResponse
import org.mpmc.protocol.Digest
import org.mpmc.protocol.EndpointId
import org.mpmc.protocol.JoinRoomRequest
import org.mpmc.protocol.JoinRoomResponse
import org.mpmc.protocol.JoinState
import org.mpmc.protocol.JoinStatusResponse
import org.mpmc.protocol.MemberRole
import org.mpmc.protocol.RejectRequest
import org.mpmc.protocol.RoomCode
import org.mpmc.protocol.RoomInfoResponse
import org.mpmc.protocol.RoomState
import org.mpmc.protocol.TicketPayload
import org.mpmc.protocol.Tokens
import org.mpmc.protocol.WsEvent
import java.time.Clock
import java.time.Instant
import java.util.UUID

class RoomService(
    private val store: Store,
    private val keys: SigningKeys,
    private val events: EventHub,
    private val metrics: Metrics,
    private val limiter: RateLimiter,
    private val clock: Clock,
    private val config: CoordinatorConfig,
) {
    suspend fun create(hostEndpointId: String, request: CreateRoomRequest): CreateRoomResponse {
        if (!ApprovalMode.isValid(request.approvalMode)) {
            throw ApiException.badRequest("INVALID_APPROVAL_MODE", "approvalMode must be prompt or invite")
        }
        if (request.maxMembers !in 2..config.maxMembersLimit) {
            throw ApiException.badRequest("INVALID_MAX_MEMBERS", "maxMembers must be between 2 and ${config.maxMembersLimit}")
        }
        if (request.ttlMinutes !in 1..config.maxTtlMinutes) {
            throw ApiException.badRequest("INVALID_TTL", "ttlMinutes must be between 1 and ${config.maxTtlMinutes}")
        }
        val label = request.label
        if (label != null && label.length > 64) {
            throw ApiException.badRequest("INVALID_LABEL", "label must be at most 64 characters")
        }

        val now = Instant.now(clock)
        val code = allocateRoomCode()
        val roomId = UUID.randomUUID()
        val hostToken = Tokens.randomB64Url(32)
        val inviteToken = Tokens.randomB64Url(32)
        val room = RoomRow(
            roomId = roomId,
            code = code,
            hostEndpointId = hostEndpointId,
            hostTokenHash = Digest.sha256(hostToken),
            inviteTokenHash = Digest.sha256(inviteToken),
            approvalMode = request.approvalMode,
            maxMembers = request.maxMembers,
            state = RoomState.ACTIVE,
            createdAt = now,
            expiresAt = now.plusSeconds(request.ttlMinutes * 60L),
            lastHeartbeat = now,
        )

        store.upsertAgent(hostEndpointId, now)
        store.createRoom(room)
        store.insertMember(roomId, hostEndpointId, MemberRole.HOST, now)
        store.audit(hostEndpointId, "room.create", roomId, """{"approvalMode":"${room.approvalMode}","maxMembers":${room.maxMembers}}""")
        metrics.setRoomsActive(store.countActiveRooms())
        events.broadcast(event("room.created", roomId))

        return CreateRoomResponse(
            roomId = roomId.toString(),
            code = code,
            inviteToken = inviteToken,
            hostToken = hostToken,
            expiresAt = room.expiresAt.toString(),
            relayUrls = config.relayUrls,
        )
    }

    suspend fun info(codeParameter: String): RoomInfoResponse {
        val room = activeRoom(findRoom(codeParameter))
        if (room.state != RoomState.ACTIVE) {
            throw ApiException.conflict("ROOM_CLOSED", "room is ${room.state}")
        }
        return RoomInfoResponse(
            roomId = room.roomId.toString(),
            hostEndpointId = room.hostEndpointId,
            memberCount = store.countMembers(room.roomId),
            state = room.state,
            approvalMode = room.approvalMode,
            expiresAt = room.expiresAt.toString(),
        )
    }

    suspend fun heartbeat(codeParameter: String, hostToken: String?) {
        val room = findRoom(codeParameter)
        verifyHost(room, hostToken)
        val current = activeRoom(room)
        if (current.state != RoomState.ACTIVE) {
            throw ApiException.conflict("ROOM_CLOSED", "room is ${current.state}")
        }
        val now = Instant.now(clock)
        store.updateHeartbeat(current.roomId, now)
        store.touchMember(current.roomId, current.hostEndpointId, now)
        store.touchAgent(current.hostEndpointId, now)
    }

    suspend fun close(codeParameter: String, hostToken: String?) {
        val room = findRoom(codeParameter)
        verifyHost(room, hostToken)
        if (room.state == RoomState.CLOSED) return

        store.setRoomState(room.roomId, RoomState.CLOSED)
        store.expireJoinRequests(Instant.now(clock))
        store.audit(room.hostEndpointId, "room.close", room.roomId)
        metrics.setRoomsActive(store.countActiveRooms())
        events.broadcast(event("room.closed", room.roomId))
    }

    suspend fun leave(codeParameter: String, endpointId: String) {
        val room = activeRoom(findRoom(codeParameter))
        if (room.state != RoomState.ACTIVE) {
            throw ApiException.conflict("ROOM_CLOSED", "room is ${room.state}")
        }
        val member = store.findMember(room.roomId, endpointId)
            ?: throw ApiException.notFound("NOT_A_MEMBER", "endpoint is not a member of this room")
        if (member.role == MemberRole.HOST) {
            throw ApiException.conflict("HOST_MUST_CLOSE", "host must close the room instead of leaving")
        }
        store.removeMember(room.roomId, endpointId)
        store.rejectApprovedRequests(room.roomId, endpointId, "left")
        store.audit(endpointId, "room.leave", room.roomId)
        events.broadcast(event("member.left", room.roomId, buildJsonObject { put("endpointId", endpointId) }))
    }

    suspend fun kick(codeParameter: String, hostToken: String?, targetEndpointId: String) {
        val room = findRoom(codeParameter)
        verifyHost(room, hostToken)
        if (!EndpointId.isValid(targetEndpointId)) {
            throw ApiException.badRequest("INVALID_ENDPOINT_ID", "endpointId must be 64 lowercase hex characters")
        }
        if (targetEndpointId == room.hostEndpointId) {
            throw ApiException.badRequest("CANNOT_KICK_HOST", "host cannot be kicked")
        }
        val removed = store.removeMember(room.roomId, targetEndpointId)
        store.rejectApprovedRequests(room.roomId, targetEndpointId, "kicked")
        if (removed) {
            store.audit(room.hostEndpointId, "room.kick", room.roomId, """{"target":"$targetEndpointId"}""")
            events.broadcast(
                event("member.left", room.roomId, buildJsonObject { put("endpointId", targetEndpointId) }),
            )
        }
    }

    suspend fun join(
        codeParameter: String,
        endpointId: String,
        request: JoinRoomRequest,
        clientIp: String,
    ): JoinRoomResponse {
        val code = RoomCode.normalize(codeParameter)
            ?: run {
                limiter.recordBadCode(clientIp)
                throw ApiException.notFound("ROOM_NOT_FOUND", "room not found")
            }
        val room = store.findRoomByCode(code)
            ?: run {
                limiter.recordBadCode(clientIp)
                throw ApiException.notFound("ROOM_NOT_FOUND", "room not found")
            }
        val current = activeRoom(room)
        if (current.state != RoomState.ACTIVE) {
            throw ApiException.conflict("ROOM_CLOSED", "room is ${current.state}")
        }

        val now = Instant.now(clock)
        store.upsertAgent(endpointId, now)

        if (store.findMember(current.roomId, endpointId) != null) {
            val requestId = insertApprovedRequest(current, endpointId, now)
            val ticket = issueTicket(current, endpointId)
            return JoinRoomResponse(requestId.toString(), JoinState.APPROVED, ticket)
        }

        val memberCount = store.countMembers(current.roomId)
        if (memberCount >= current.maxMembers) {
            throw ApiException.conflict("ROOM_FULL", "room is full")
        }

        val inviteToken = request.inviteToken
        val inviteMatches = inviteToken != null &&
            ConstantTime.equals(Digest.sha256(inviteToken), current.inviteTokenHash)
        if (current.approvalMode == ApprovalMode.INVITE && inviteMatches) {
            val requestId = insertApprovedRequest(current, endpointId, now)
            store.insertMember(current.roomId, endpointId, MemberRole.GUEST, now)
            store.audit(endpointId, "join.approved", current.roomId, """{"requestId":"$requestId"}""")
            store.audit(endpointId, "member.joined", current.roomId)
            metrics.incJoin(JoinState.APPROVED)
            events.broadcast(
                event("join.approved", current.roomId, buildJsonObject { put("joinRequestId", requestId.toString()) }),
            )
            events.broadcast(
                event("member.joined", current.roomId, buildJsonObject { put("endpointId", endpointId) }),
            )
            val ticket = issueTicket(current, endpointId)
            return JoinRoomResponse(requestId.toString(), JoinState.APPROVED, ticket)
        }

        if (current.approvalMode == ApprovalMode.INVITE) {
            limiter.recordBadCode(clientIp)
        }
        store.expirePendingJoinRequests(current.roomId, endpointId)
        val requestId = UUID.randomUUID()
        store.insertJoinRequest(
            JoinRequestRow(
                requestId = requestId,
                roomId = current.roomId,
                endpointId = endpointId,
                state = JoinState.PENDING,
                reason = null,
                createdAt = now,
                expiresAt = now.plusSeconds(config.joinRequestTtlSeconds),
                approvedAt = null,
            ),
        )
        store.audit(endpointId, "join.requested", current.roomId, """{"requestId":"$requestId"}""")
        metrics.incJoin(JoinState.PENDING)
        events.broadcast(
            event("join.requested", current.roomId, buildJsonObject { put("joinRequestId", requestId.toString()) }),
        )
        return JoinRoomResponse(requestId.toString(), JoinState.PENDING, null)
    }

    suspend fun joinStatus(requestId: UUID, endpointId: String): JoinStatusResponse {
        val request = store.findJoinRequest(requestId)
            ?: throw ApiException.notFound("JOIN_REQUEST_NOT_FOUND", "join request not found")
        if (request.endpointId != endpointId) {
            throw ApiException.forbidden("join request belongs to another endpoint")
        }
        val room = store.findRoomById(request.roomId)
            ?: throw ApiException.notFound("ROOM_NOT_FOUND", "room not found")
        val current = activeRoom(room)
        val now = Instant.now(clock)

        if (request.state == JoinState.PENDING && !request.expiresAt.isAfter(now)) {
            store.transitionJoinRequest(requestId, JoinState.EXPIRED, null, null)
            metrics.incJoin(JoinState.EXPIRED)
            return JoinStatusResponse(JoinState.EXPIRED, reason = "request_expired")
        }

        if (current.state != RoomState.ACTIVE) {
            if (request.state != JoinState.REJECTED && request.state != JoinState.EXPIRED) {
                store.setJoinRequestState(requestId, JoinState.REJECTED, "room_closed", request.approvedAt)
                metrics.incJoin(JoinState.REJECTED)
            }
            return JoinStatusResponse(JoinState.REJECTED, reason = "room_closed")
        }

        return when (request.state) {
            JoinState.PENDING -> JoinStatusResponse(JoinState.PENDING)
            JoinState.REJECTED, JoinState.EXPIRED -> JoinStatusResponse(request.state, reason = request.reason)
            JoinState.APPROVED -> {
                val member = store.findMember(current.roomId, endpointId)
                if (member == null) {
                    store.setJoinRequestState(requestId, JoinState.REJECTED, "removed", request.approvedAt)
                    metrics.incJoin(JoinState.REJECTED)
                    JoinStatusResponse(JoinState.REJECTED, reason = "removed")
                } else {
                    store.touchMember(current.roomId, endpointId, now)
                    store.touchAgent(endpointId, now)
                    val latest = store.latestTicket(current.roomId, endpointId)
                    val ticket = if (latest != null && latest.expiresAt.isAfter(now.plusSeconds(5))) {
                        ticketFor(current, endpointId, latest)
                    } else {
                        issueTicket(current, endpointId)
                    }
                    JoinStatusResponse(JoinState.APPROVED, ticket = ticket)
                }
            }
            else -> JoinStatusResponse(request.state, reason = request.reason)
        }
    }

    suspend fun approve(requestId: UUID, hostToken: String?): ApproveResponse {
        val request = store.findJoinRequest(requestId)
            ?: throw ApiException.notFound("JOIN_REQUEST_NOT_FOUND", "join request not found")
        val room = store.findRoomById(request.roomId)
            ?: throw ApiException.notFound("ROOM_NOT_FOUND", "room not found")
        verifyHost(room, hostToken)

        val now = Instant.now(clock)
        if (request.state != JoinState.PENDING) {
            throw ApiException.conflict("REQUEST_NOT_PENDING", "join request is ${request.state}")
        }
        if (!request.expiresAt.isAfter(now)) {
            store.transitionJoinRequest(requestId, JoinState.EXPIRED, null, null)
            metrics.incJoin(JoinState.EXPIRED)
            throw ApiException.conflict("REQUEST_EXPIRED", "join request expired")
        }
        val current = activeRoom(room)
        if (current.state != RoomState.ACTIVE) {
            throw ApiException.conflict("ROOM_CLOSED", "room is ${current.state}")
        }

        if (store.findMember(current.roomId, request.endpointId) == null) {
            if (store.countMembers(current.roomId) >= current.maxMembers) {
                throw ApiException.conflict("ROOM_FULL", "room is full")
            }
            store.insertMember(current.roomId, request.endpointId, MemberRole.GUEST, now)
        }
        if (!store.transitionJoinRequest(requestId, JoinState.APPROVED, null, now)) {
            throw ApiException.conflict("REQUEST_NOT_PENDING", "join request changed state concurrently")
        }

        store.audit(current.hostEndpointId, "join.approve", current.roomId, """{"requestId":"$requestId"}""")
        store.audit(request.endpointId, "member.joined", current.roomId)
        metrics.incJoin(JoinState.APPROVED)
        events.broadcast(
            event("join.approved", current.roomId, buildJsonObject { put("joinRequestId", requestId.toString()) }),
        )
        events.broadcast(
            event("member.joined", current.roomId, buildJsonObject { put("endpointId", request.endpointId) }),
        )
        return ApproveResponse(issueTicket(current, request.endpointId))
    }

    suspend fun reject(requestId: UUID, hostToken: String?, requestBody: RejectRequest) {
        val request = store.findJoinRequest(requestId)
            ?: throw ApiException.notFound("JOIN_REQUEST_NOT_FOUND", "join request not found")
        val room = store.findRoomById(request.roomId)
            ?: throw ApiException.notFound("ROOM_NOT_FOUND", "room not found")
        verifyHost(room, hostToken)

        val reason = requestBody.reason?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
        if (!store.transitionJoinRequest(requestId, JoinState.REJECTED, reason, null)) {
            throw ApiException.conflict("REQUEST_NOT_PENDING", "join request is ${request.state}")
        }
        store.audit(room.hostEndpointId, "join.reject", room.roomId, """{"requestId":"$requestId"}""")
        metrics.incJoin(JoinState.REJECTED)
        events.broadcast(
            event("join.rejected", room.roomId, buildJsonObject { put("joinRequestId", requestId.toString()) }),
        )
    }

    private suspend fun allocateRoomCode(): String {
        repeat(5) {
            val candidate = RoomCode.generate()
            if (!store.roomCodeExists(candidate)) return candidate
        }
        throw IllegalStateException("could not allocate a unique room code in 5 attempts")
    }

    private suspend fun findRoom(codeParameter: String): RoomRow {
        val code = RoomCode.normalize(codeParameter)
            ?: throw ApiException.notFound("ROOM_NOT_FOUND", "room not found")
        return store.findRoomByCode(code)
            ?: throw ApiException.notFound("ROOM_NOT_FOUND", "room not found")
    }

    private suspend fun activeRoom(room: RoomRow): RoomRow {
        if (room.state != RoomState.ACTIVE) return room
        val now = Instant.now(clock)
        val stale = room.lastHeartbeat.isBefore(now.minusSeconds(config.roomHeartbeatTimeoutSeconds))
        if (!room.expiresAt.isAfter(now) || stale) {
            if (store.setRoomStateIfActive(room.roomId, RoomState.EXPIRED)) {
                store.audit(null, "room.expired", room.roomId)
                metrics.setRoomsActive(store.countActiveRooms())
            }
            return room.copy(state = RoomState.EXPIRED)
        }
        return room
    }

    private fun verifyHost(room: RoomRow, hostToken: String?) {
        val token = hostToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException.forbidden("missing host token")
        if (!ConstantTime.equals(Digest.sha256(token), room.hostTokenHash)) {
            throw ApiException.forbidden("invalid host token")
        }
    }

    private suspend fun insertApprovedRequest(room: RoomRow, endpointId: String, now: Instant): UUID {
        val requestId = UUID.randomUUID()
        store.insertJoinRequest(
            JoinRequestRow(
                requestId = requestId,
                roomId = room.roomId,
                endpointId = endpointId,
                state = JoinState.APPROVED,
                reason = null,
                createdAt = now,
                expiresAt = now.plusSeconds(config.joinRequestTtlSeconds),
                approvedAt = now,
            ),
        )
        return requestId
    }

    private suspend fun issueTicket(room: RoomRow, endpointId: String): String {
        val now = Instant.now(clock)
        val expiresAt = now.plusSeconds(config.ticketTtlSeconds)
        val payload = TicketPayload(
            roomId = room.roomId.toString(),
            code = room.code,
            sub = endpointId,
            jti = UUID.randomUUID().toString(),
            iat = now.epochSecond,
            exp = expiresAt.epochSecond,
        )
        val ticket = keys.issueTicket(payload)
        store.insertTicket(UUID.fromString(payload.jti), room.roomId, endpointId, now, expiresAt, payload.maxStreams)
        store.upsertGrant(endpointId, expiresAt.plusSeconds(config.relayGrantSeconds), "ticket")
        metrics.incTicketIssued()
        return ticket
    }

    private fun ticketFor(room: RoomRow, endpointId: String, row: TicketRow): String =
        keys.issueTicket(
            TicketPayload(
                roomId = room.roomId.toString(),
                code = room.code,
                sub = endpointId,
                jti = row.jti.toString(),
                iat = row.issuedAt.epochSecond,
                exp = row.expiresAt.epochSecond,
                maxStreams = row.maxStreams,
            ),
        )

    private fun event(type: String, roomId: UUID, data: JsonObject = JsonObject(emptyMap())): WsEvent =
        WsEvent(type = type, roomId = roomId.toString(), ts = Instant.now(clock).toString(), data = data)
}
