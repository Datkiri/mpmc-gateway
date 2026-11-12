package org.mpmc.coordinator.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class Store(
    private val dataSource: DataSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private suspend fun <T> withConnection(block: (Connection) -> T): T =
        withContext(io) { dataSource.connection.use(block) }

    suspend fun upsertAgent(endpointId: String, now: Instant) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into agents (endpoint_id, last_seen_at) values (?, ?)
            on conflict (endpoint_id) do update set last_seen_at = excluded.last_seen_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, endpointId)
            statement.setObject(2, now.asOffset())
            statement.executeUpdate()
        }
    }

    suspend fun touchAgent(endpointId: String, now: Instant) = withConnection { connection ->
        connection.prepareStatement("update agents set last_seen_at = ? where endpoint_id = ?").use { statement ->
            statement.setObject(1, now.asOffset())
            statement.setString(2, endpointId)
            statement.executeUpdate()
        }
    }

    suspend fun findAgent(endpointId: String): AgentRow? = withConnection { connection ->
        connection.prepareStatement("select endpoint_id, last_seen_at, banned_until from agents where endpoint_id = ?")
            .use { statement ->
                statement.setString(1, endpointId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toAgent() else null }
            }
    }

    suspend fun roomCodeExists(code: String): Boolean = withConnection { connection ->
        connection.prepareStatement("select 1 from rooms where code = ?").use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    suspend fun createRoom(room: RoomRow) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into rooms (room_id, code, host_endpoint_id, host_token_hash, invite_token_hash,
                               approval_mode, max_members, state, created_at, expires_at, last_heartbeat)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, room.roomId)
            statement.setString(2, room.code)
            statement.setString(3, room.hostEndpointId)
            statement.setBytes(4, room.hostTokenHash)
            statement.setBytes(5, room.inviteTokenHash)
            statement.setString(6, room.approvalMode)
            statement.setInt(7, room.maxMembers)
            statement.setString(8, room.state)
            statement.setObject(9, room.createdAt.asOffset())
            statement.setObject(10, room.expiresAt.asOffset())
            statement.setObject(11, room.lastHeartbeat.asOffset())
            statement.executeUpdate()
        }
    }

    suspend fun findRoomByCode(code: String): RoomRow? = withConnection { connection ->
        connection.prepareStatement("select * from rooms where code = ?").use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toRoom() else null }
        }
    }

    suspend fun findRoomById(roomId: UUID): RoomRow? = withConnection { connection ->
        connection.prepareStatement("select * from rooms where room_id = ?").use { statement ->
            statement.setObject(1, roomId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toRoom() else null }
        }
    }

    suspend fun updateHeartbeat(roomId: UUID, now: Instant) = withConnection { connection ->
        connection.prepareStatement("update rooms set last_heartbeat = ? where room_id = ?").use { statement ->
            statement.setObject(1, now.asOffset())
            statement.setObject(2, roomId)
            statement.executeUpdate()
        }
    }

    suspend fun setRoomState(roomId: UUID, state: String) = withConnection { connection ->
        connection.prepareStatement("update rooms set state = ? where room_id = ?").use { statement ->
            statement.setString(1, state)
            statement.setObject(2, roomId)
            statement.executeUpdate()
        }
    }

    suspend fun setRoomStateIfActive(roomId: UUID, state: String): Boolean = withConnection { connection ->
        connection.prepareStatement("update rooms set state = ? where room_id = ? and state = 'active'").use { statement ->
            statement.setString(1, state)
            statement.setObject(2, roomId)
            statement.executeUpdate() > 0
        }
    }

    suspend fun expireStaleRooms(heartbeatCutoff: Instant, now: Instant): Int = withConnection { connection ->
        connection.prepareStatement(
            "update rooms set state = 'expired' where state = 'active' and (last_heartbeat < ? or expires_at <= ?)",
        ).use { statement ->
            statement.setObject(1, heartbeatCutoff.asOffset())
            statement.setObject(2, now.asOffset())
            statement.executeUpdate()
        }
    }

    suspend fun countActiveRooms(): Int = withConnection { connection ->
        connection.prepareStatement("select count(*) from rooms where state = 'active'").use { statement ->
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    suspend fun countMembers(roomId: UUID): Int = withConnection { connection ->
        connection.prepareStatement("select count(*) from members where room_id = ?").use { statement ->
            statement.setObject(1, roomId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    suspend fun insertMember(roomId: UUID, endpointId: String, role: String, now: Instant) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into members (room_id, endpoint_id, role, joined_at, last_seen) values (?, ?, ?, ?, ?)
            on conflict (room_id, endpoint_id) do update set last_seen = excluded.last_seen
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, roomId)
            statement.setString(2, endpointId)
            statement.setString(3, role)
            statement.setObject(4, now.asOffset())
            statement.setObject(5, now.asOffset())
            statement.executeUpdate()
        }
    }

    suspend fun findMember(roomId: UUID, endpointId: String): MemberRow? = withConnection { connection ->
        connection.prepareStatement("select * from members where room_id = ? and endpoint_id = ?").use { statement ->
            statement.setObject(1, roomId)
            statement.setString(2, endpointId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toMember() else null }
        }
    }

    suspend fun touchMember(roomId: UUID, endpointId: String, now: Instant) = withConnection { connection ->
        connection.prepareStatement("update members set last_seen = ? where room_id = ? and endpoint_id = ?").use { statement ->
            statement.setObject(1, now.asOffset())
            statement.setObject(2, roomId)
            statement.setString(3, endpointId)
            statement.executeUpdate()
        }
    }

    suspend fun removeMember(roomId: UUID, endpointId: String): Boolean = withConnection { connection ->
        connection.prepareStatement("delete from members where room_id = ? and endpoint_id = ?").use { statement ->
            statement.setObject(1, roomId)
            statement.setString(2, endpointId)
            statement.executeUpdate() > 0
        }
    }

    suspend fun insertJoinRequest(request: JoinRequestRow) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into join_requests (request_id, room_id, endpoint_id, state, reason, created_at, expires_at, approved_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, request.requestId)
            statement.setObject(2, request.roomId)
            statement.setString(3, request.endpointId)
            statement.setString(4, request.state)
            statement.setString(5, request.reason)
            statement.setObject(6, request.createdAt.asOffset())
            statement.setObject(7, request.expiresAt.asOffset())
            statement.setObject(8, request.approvedAt?.asOffset())
            statement.executeUpdate()
        }
    }

    suspend fun expirePendingJoinRequests(roomId: UUID, endpointId: String) = withConnection { connection ->
        connection.prepareStatement(
            "update join_requests set state = 'expired' where room_id = ? and endpoint_id = ? and state = 'pending'",
        ).use { statement ->
            statement.setObject(1, roomId)
            statement.setString(2, endpointId)
            statement.executeUpdate()
        }
    }

    suspend fun findJoinRequest(requestId: UUID): JoinRequestRow? = withConnection { connection ->
        connection.prepareStatement("select * from join_requests where request_id = ?").use { statement ->
            statement.setObject(1, requestId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toJoinRequest() else null }
        }
    }

    suspend fun transitionJoinRequest(
        requestId: UUID,
        state: String,
        reason: String?,
        approvedAt: Instant?,
    ): Boolean = withConnection { connection ->
        connection.prepareStatement(
            """
            update join_requests set state = ?, reason = coalesce(?, reason), approved_at = coalesce(?, approved_at)
            where request_id = ? and state = 'pending'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, state)
            statement.setString(2, reason)
            statement.setObject(3, approvedAt?.asOffset())
            statement.setObject(4, requestId)
            statement.executeUpdate() > 0
        }
    }

    suspend fun setJoinRequestState(requestId: UUID, state: String, reason: String?, approvedAt: Instant?) =
        withConnection { connection ->
            connection.prepareStatement(
                "update join_requests set state = ?, reason = ?, approved_at = ? where request_id = ?",
            ).use { statement ->
                statement.setString(1, state)
                statement.setString(2, reason)
                statement.setObject(3, approvedAt?.asOffset())
                statement.setObject(4, requestId)
                statement.executeUpdate()
            }
        }

    suspend fun rejectApprovedRequests(roomId: UUID, endpointId: String, reason: String) = withConnection { connection ->
        connection.prepareStatement(
            "update join_requests set state = 'rejected', reason = ? where room_id = ? and endpoint_id = ? and state = 'approved'",
        ).use { statement ->
            statement.setString(1, reason)
            statement.setObject(2, roomId)
            statement.setString(3, endpointId)
            statement.executeUpdate()
        }
    }

    suspend fun expireJoinRequests(now: Instant): Int = withConnection { connection ->
        connection.prepareStatement(
            "update join_requests set state = 'expired' where state = 'pending' and expires_at <= ?",
        ).use { statement ->
            statement.setObject(1, now.asOffset())
            statement.executeUpdate()
        }
    }

    suspend fun insertTicket(
        jti: UUID,
        roomId: UUID,
        endpointId: String,
        issuedAt: Instant,
        expiresAt: Instant,
        maxStreams: Int,
    ) = withConnection { connection ->
        connection.prepareStatement(
            "insert into tickets (jti, room_id, endpoint_id, issued_at, expires_at, max_streams) values (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, jti)
            statement.setObject(2, roomId)
            statement.setString(3, endpointId)
            statement.setObject(4, issuedAt.asOffset())
            statement.setObject(5, expiresAt.asOffset())
            statement.setInt(6, maxStreams)
            statement.executeUpdate()
        }
    }

    suspend fun latestTicket(roomId: UUID, endpointId: String): TicketRow? = withConnection { connection ->
        connection.prepareStatement(
            "select * from tickets where room_id = ? and endpoint_id = ? order by issued_at desc limit 1",
        ).use { statement ->
            statement.setObject(1, roomId)
            statement.setString(2, endpointId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toTicket() else null }
        }
    }

    suspend fun hasValidGrant(endpointId: String, now: Instant): Boolean = withConnection { connection ->
        connection.prepareStatement("select 1 from relay_grants where endpoint_id = ? and granted_until > ?").use { statement ->
            statement.setString(1, endpointId)
            statement.setObject(2, now.asOffset())
            statement.executeQuery().use { rs -> rs.next() }
        }
    }

    suspend fun upsertGrant(endpointId: String, until: Instant, reason: String?) = withConnection { connection ->
        connection.prepareStatement(
            """
            insert into relay_grants (endpoint_id, granted_until, reason) values (?, ?, ?)
            on conflict (endpoint_id) do update set granted_until = excluded.granted_until, reason = excluded.reason
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, endpointId)
            statement.setObject(2, until.asOffset())
            statement.setString(3, reason)
            statement.executeUpdate()
        }
    }

    suspend fun hasRecentActiveMembership(endpointId: String, since: Instant): Boolean = withConnection { connection ->
        connection.prepareStatement(
            """
            select exists(
                select 1 from members m join rooms r on r.room_id = m.room_id
                where m.endpoint_id = ? and m.last_seen >= ? and r.state = 'active'
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, endpointId)
            statement.setObject(2, since.asOffset())
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    suspend fun audit(actor: String?, action: String, roomId: UUID?, detailJson: String? = null) =
        withConnection { connection ->
            connection.prepareStatement(
                "insert into audit_log (actor, action, room_id, detail) values (?, ?, ?, ?::jsonb)",
            ).use { statement ->
                statement.setString(1, actor)
                statement.setString(2, action)
                statement.setObject(3, roomId)
                statement.setString(4, detailJson)
                statement.executeUpdate()
            }
        }

    private fun Instant.asOffset(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun ResultSet.instant(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.toAgent(): AgentRow = AgentRow(
        endpointId = getString("endpoint_id"),
        lastSeenAt = instant("last_seen_at"),
        bannedUntil = instant("banned_until"),
    )

    private fun ResultSet.toRoom(): RoomRow = RoomRow(
        roomId = getObject("room_id", UUID::class.java),
        code = getString("code").trim(),
        hostEndpointId = getString("host_endpoint_id"),
        hostTokenHash = getBytes("host_token_hash"),
        inviteTokenHash = getBytes("invite_token_hash"),
        approvalMode = getString("approval_mode"),
        maxMembers = getInt("max_members"),
        state = getString("state"),
        createdAt = instant("created_at")!!,
        expiresAt = instant("expires_at")!!,
        lastHeartbeat = instant("last_heartbeat")!!,
    )

    private fun ResultSet.toMember(): MemberRow = MemberRow(
        roomId = getObject("room_id", UUID::class.java),
        endpointId = getString("endpoint_id"),
        role = getString("role"),
        joinedAt = instant("joined_at")!!,
        lastSeen = instant("last_seen")!!,
    )

    private fun ResultSet.toJoinRequest(): JoinRequestRow = JoinRequestRow(
        requestId = getObject("request_id", UUID::class.java),
        roomId = getObject("room_id", UUID::class.java),
        endpointId = getString("endpoint_id"),
        state = getString("state"),
        reason = getString("reason"),
        createdAt = instant("created_at")!!,
        expiresAt = instant("expires_at")!!,
        approvedAt = instant("approved_at"),
    )

    private fun ResultSet.toTicket(): TicketRow = TicketRow(
        jti = getObject("jti", UUID::class.java),
        roomId = getObject("room_id", UUID::class.java),
        endpointId = getString("endpoint_id"),
        issuedAt = instant("issued_at")!!,
        expiresAt = instant("expires_at")!!,
        maxStreams = getInt("max_streams"),
    )
}
