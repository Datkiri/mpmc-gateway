package org.mpmc.coordinator.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import org.mpmc.coordinator.auth.AuthService
import org.mpmc.coordinator.http.RateLimiter.Decision
import org.mpmc.coordinator.metrics.Metrics

object MpmcHeaders {
    const val HOST_TOKEN = "X-Mpmc-Host-Token"
    const val IROH_ENDPOINT_ID = "X-Iroh-Endpoint-Id"
}

fun ApplicationCall.clientIp(): String = request.origin.remoteHost

fun ApplicationCall.hostToken(): String? = request.headers[MpmcHeaders.HOST_TOKEN]

suspend inline fun ApplicationCall.metered(
    metrics: Metrics,
    route: String,
    crossinline block: suspend () -> Unit,
) {
    try {
        block()
    } finally {
        metrics.observeHttp(route, response.status()?.value ?: 500)
    }
}

suspend fun ApplicationCall.requireEndpoint(auth: AuthService, limiter: RateLimiter): String {
    val endpointId = auth.authenticateBearer(request.headers[HttpHeaders.Authorization])
        ?: throw ApiException.unauthorized("missing or invalid access token")
    when (val decision = limiter.checkAnyRequest(endpointId)) {
        is Decision.Limited -> throw ApiException.tooManyRequests(decision.retryAfterSeconds)
        Decision.Allowed -> Unit
    }
    return endpointId
}
