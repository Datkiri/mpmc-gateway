package org.mpmc.coordinator.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.mpmc.coordinator.http.metered
import org.mpmc.coordinator.metrics.Metrics
import org.mpmc.protocol.ChallengeRequest
import org.mpmc.protocol.TokenRequest

fun Route.authRoutes(auth: AuthService, metrics: Metrics) {
    post("/v1/auth/challenge") {
        call.metered(metrics, "auth.challenge") {
            call.respond(auth.challenge(call.receive<ChallengeRequest>()))
        }
    }
    post("/v1/auth/token") {
        call.metered(metrics, "auth.token") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(auth.token(call.receive<TokenRequest>()))
        }
    }
}
