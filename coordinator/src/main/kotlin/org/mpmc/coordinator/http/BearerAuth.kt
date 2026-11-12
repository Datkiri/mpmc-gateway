package org.mpmc.coordinator.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import org.mpmc.coordinator.auth.AuthService

class EndpointPrincipal(val endpointId: String)

class BearerTokenProvider(
    private val providerName: String,
    private val auth: AuthService,
) : AuthenticationProvider(Config(providerName)) {
    class Config(name: String) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val endpointId = auth.authenticateBearer(context.call.request.headers[HttpHeaders.Authorization])
        if (endpointId == null) {
            context.challenge(providerName, AuthenticationFailedCause.InvalidCredentials) { _, call ->
                call.respondProblem(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "missing or invalid access token")
            }
            return
        }
        context.principal(EndpointPrincipal(endpointId))
    }
}

fun AuthenticationConfig.mpmcBearer(name: String, auth: AuthService) {
    register(BearerTokenProvider(name, auth))
}
