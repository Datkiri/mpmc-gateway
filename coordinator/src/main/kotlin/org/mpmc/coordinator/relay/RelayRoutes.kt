package org.mpmc.coordinator.relay

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.mpmc.coordinator.config.CoordinatorConfig
import org.mpmc.coordinator.http.MpmcHeaders
import org.mpmc.coordinator.http.metered
import org.mpmc.coordinator.metrics.Metrics
import org.mpmc.coordinator.store.Store
import org.mpmc.protocol.ConstantTime
import org.mpmc.protocol.EndpointId
import java.time.Clock
import java.time.Instant

class RelayAuthorizer(
    private val store: Store,
    private val config: CoordinatorConfig,
    private val clock: Clock,
    private val metrics: Metrics,
) {
    suspend fun authorize(endpointIdHeader: String?, authorizationHeader: String?): Boolean {
        val presentedToken = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
            ?: ""
        if (!ConstantTime.equalsDigest(
                presentedToken.toByteArray(Charsets.UTF_8),
                config.relayCallbackToken.toByteArray(Charsets.UTF_8),
            )
        ) {
            metrics.incRelay("deny")
            return false
        }

        val endpointId = endpointIdHeader?.trim()
        if (endpointId == null || !EndpointId.isValid(endpointId)) {
            metrics.incRelay("deny")
            return false
        }

        val now = Instant.now(clock)
        val agent = store.findAgent(endpointId)
        if (agent == null || agent.bannedUntil?.isAfter(now) == true) {
            metrics.incRelay("deny")
            return false
        }

        val allowed = store.hasValidGrant(endpointId, now) ||
            store.hasRecentActiveMembership(endpointId, now.minusSeconds(config.memberLastSeenSeconds))
        metrics.incRelay(if (allowed) "allow" else "deny")
        return allowed
    }
}

fun Route.relayRoutes(authorizer: RelayAuthorizer, metrics: Metrics) {
    post("/v1/relay/authorize") {
        call.metered(metrics, "relay.authorize") { handleRelayAuthorize(call, authorizer) }
    }
    get("/v1/relay/authorize") {
        call.metered(metrics, "relay.authorize") { handleRelayAuthorize(call, authorizer) }
    }
}

private suspend fun handleRelayAuthorize(call: ApplicationCall, authorizer: RelayAuthorizer) {
    val allowed = authorizer.authorize(
        endpointIdHeader = call.request.headers[MpmcHeaders.IROH_ENDPOINT_ID],
        authorizationHeader = call.request.headers[HttpHeaders.Authorization],
    )
    call.respondText(
        text = if (allowed) "true" else "false",
        contentType = ContentType.Text.Plain,
        status = if (allowed) HttpStatusCode.OK else HttpStatusCode.Forbidden,
    )
}
