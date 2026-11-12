package org.mpmc.coordinator.rooms

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.mpmc.coordinator.auth.AuthService
import org.mpmc.coordinator.http.ApiException
import org.mpmc.coordinator.http.RateLimiter
import org.mpmc.coordinator.http.RateLimiter.Decision
import org.mpmc.coordinator.http.clientIp
import org.mpmc.coordinator.http.hostToken
import org.mpmc.coordinator.http.metered
import org.mpmc.coordinator.http.requireEndpoint
import org.mpmc.coordinator.metrics.Metrics
import org.mpmc.protocol.CreateRoomRequest
import org.mpmc.protocol.JoinRoomRequest
import org.mpmc.protocol.KickRequest
import org.mpmc.protocol.RejectRequest
import java.util.UUID

fun Route.roomRoutes(
    rooms: RoomService,
    auth: AuthService,
    limiter: RateLimiter,
    metrics: Metrics,
) {
    post("/v1/rooms") {
        val endpointId = call.requireEndpoint(auth, limiter)
        call.metered(metrics, "rooms.create") {
            when (val decision = limiter.checkCreate(call.clientIp(), endpointId)) {
                is Decision.Limited -> throw ApiException.tooManyRequests(decision.retryAfterSeconds)
                Decision.Allowed -> Unit
            }
            val request = call.receive<CreateRoomRequest>()
            call.respond(HttpStatusCode.Created, rooms.create(endpointId, request))
        }
    }

    get("/v1/rooms/{code}") {
        call.requireEndpoint(auth, limiter)
        call.metered(metrics, "rooms.info") {
            call.respond(rooms.info(call.codeParameter()))
        }
    }

    post("/v1/rooms/{code}/heartbeat") {
        call.metered(metrics, "rooms.heartbeat") {
            rooms.heartbeat(call.codeParameter(), call.hostToken())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    delete("/v1/rooms/{code}") {
        call.metered(metrics, "rooms.close") {
            rooms.close(call.codeParameter(), call.hostToken())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/v1/rooms/{code}/leave") {
        val endpointId = call.requireEndpoint(auth, limiter)
        call.metered(metrics, "rooms.leave") {
            rooms.leave(call.codeParameter(), endpointId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/v1/rooms/{code}/kick") {
        call.metered(metrics, "rooms.kick") {
            val request = call.receive<KickRequest>()
            rooms.kick(call.codeParameter(), call.hostToken(), request.endpointId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/v1/rooms/{code}/join") {
        val endpointId = call.requireEndpoint(auth, limiter)
        call.metered(metrics, "rooms.join") {
            val ip = call.clientIp()
            when (val decision = limiter.checkJoin(ip, endpointId)) {
                is Decision.Limited -> throw ApiException.tooManyRequests(decision.retryAfterSeconds)
                Decision.Allowed -> Unit
            }
            val request = call.receive<JoinRoomRequest>()
            call.respond(rooms.join(call.codeParameter(), endpointId, request, ip))
        }
    }

    get("/v1/join-requests/{id}") {
        val endpointId = call.requireEndpoint(auth, limiter)
        call.metered(metrics, "join.status") {
            call.respond(rooms.joinStatus(call.uuidParameter("id"), endpointId))
        }
    }

    post("/v1/join-requests/{id}/approve") {
        call.metered(metrics, "join.approve") {
            call.respond(rooms.approve(call.uuidParameter("id"), call.hostToken()))
        }
    }

    post("/v1/join-requests/{id}/reject") {
        call.metered(metrics, "join.reject") {
            val request = call.receive<RejectRequest>()
            rooms.reject(call.uuidParameter("id"), call.hostToken(), request)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.codeParameter(): String =
    parameters["code"] ?: throw ApiException.badRequest("INVALID_ROOM_CODE", "missing room code")

private fun io.ktor.server.application.ApplicationCall.uuidParameter(name: String): UUID {
    val value = parameters[name] ?: throw ApiException.badRequest("INVALID_ID", "missing $name")
    return runCatching { UUID.fromString(value) }
        .getOrElse { throw ApiException.badRequest("INVALID_ID", "$name must be a UUID") }
}
