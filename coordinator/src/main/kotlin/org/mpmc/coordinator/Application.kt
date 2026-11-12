package org.mpmc.coordinator

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.mpmc.coordinator.auth.AuthService
import org.mpmc.coordinator.auth.ChallengeStore
import org.mpmc.coordinator.auth.SigningKeys
import org.mpmc.coordinator.auth.authRoutes
import org.mpmc.coordinator.config.CoordinatorConfig
import org.mpmc.coordinator.events.EventHub
import org.mpmc.coordinator.http.ApiException
import org.mpmc.coordinator.http.RateLimiter
import org.mpmc.coordinator.http.metered
import org.mpmc.coordinator.http.mpmcBearer
import org.mpmc.coordinator.http.respondProblem
import org.mpmc.coordinator.metrics.Metrics
import org.mpmc.coordinator.relay.RelayAuthorizer
import org.mpmc.coordinator.relay.relayRoutes
import org.mpmc.coordinator.rooms.RoomService
import org.mpmc.coordinator.rooms.roomRoutes
import org.mpmc.coordinator.store.Database
import org.mpmc.coordinator.store.Store
import org.mpmc.protocol.JoinState
import org.mpmc.protocol.JwksResponse
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import kotlin.system.exitProcess

fun main() {
    val config = try {
        CoordinatorConfig.fromEnv()
    } catch (e: Exception) {
        System.err.println("config_error: ${e.message}")
        exitProcess(2)
    }
    val database = try {
        Database.connect(config.databaseUrl, config.databaseUser, config.databasePassword).also { it.migrate() }
    } catch (e: Exception) {
        System.err.println("database_error: ${e::class.simpleName}: ${e.message}")
        exitProcess(3)
    }
    val keys = try {
        SigningKeys.loadOrCreate(config.signingKeyPath)
    } catch (e: Exception) {
        System.err.println("signing_key_error: ${e::class.simpleName}: ${e.message}")
        exitProcess(4)
    }

    Runtime.getRuntime().addShutdownHook(Thread { database.close() })
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config, database, keys)
    }.start(wait = true)
}

fun Application.module(
    config: CoordinatorConfig,
    database: Database,
    keys: SigningKeys,
    clock: Clock = Clock.systemUTC(),
) {
    val store = Store(database.dataSource)
    val metrics = Metrics()
    val challenges = ChallengeStore(clock, config.challengeTtlSeconds)
    val auth = AuthService(store, keys, challenges, clock, metrics, config.accessTokenTtlSeconds)
    val limiter = RateLimiter(clock)
    val events = EventHub()
    val rooms = RoomService(store, keys, events, metrics, limiter, clock, config)
    val relayAuthorizer = RelayAuthorizer(store, config, clock, metrics)

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
    install(WebSockets)
    install(RequestBodyLimit) {
        bodyLimit { config.maxBodyBytes }
    }
    install(Authentication) {
        mpmcBearer("mpmc-bearer", auth)
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path() != "/metrics" }
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} " +
                "${call.response.status()?.value ?: 0} ip=${call.request.origin.remoteHost}"
        }
    }
    install(io.ktor.server.plugins.statuspages.StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondProblem(cause.status, cause.code, cause.message ?: cause.status.description, cause.headers)
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondProblem(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", "request body exceeds 16 KiB")
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondProblem(HttpStatusCode.BadRequest, "INVALID_JSON", "request body is not valid JSON")
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled_error type=${cause::class.simpleName}", cause)
            call.respondProblem(HttpStatusCode.InternalServerError, "INTERNAL", "internal error")
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondProblem(HttpStatusCode.NotFound, "NOT_FOUND", "no such route")
        }
    }

    routing {
        get("/healthz") {
            call.metered(metrics, "healthz") {
                call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
            }
        }
        get("/metrics") {
            call.respondText(
                text = metrics.render(),
                contentType = ContentType.parse("text/plain; version=0.0.4"),
                status = HttpStatusCode.OK,
            )
        }
        get("/v1/.well-known/mpmc-keys") {
            call.metered(metrics, "jwks") {
                call.response.headers.append("Cache-Control", "public, max-age=600")
                call.respond(JwksResponse(listOf(keys.jwk())))
            }
        }

        authRoutes(auth, metrics)
        roomRoutes(rooms, auth, limiter, metrics)
        relayRoutes(relayAuthorizer, metrics)

        authenticate("mpmc-bearer") {
            webSocket("/v1/events") {
                val session = this
                events.add(session)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Close) break
                    }
                } finally {
                    events.remove(session)
                }
            }
        }
    }

    val sweeper = launch(Dispatchers.Default) {
        while (isActive) {
            delay(config.sweeperIntervalSeconds * 1000L)
            try {
                val now = Instant.now(clock)
                store.expireStaleRooms(now.minusSeconds(config.roomHeartbeatTimeoutSeconds), now)
                val expiredRequests = store.expireJoinRequests(now)
                if (expiredRequests > 0) metrics.incJoin(JoinState.EXPIRED, expiredRequests.toLong())
                challenges.purgeExpired()
                limiter.purge()
                metrics.setRoomsActive(store.countActiveRooms())
            } catch (e: Exception) {
                log.warn("sweeper_failed type=${e::class.simpleName}")
            }
        }
    }
    launch(Dispatchers.IO) {
        runCatching { metrics.setRoomsActive(store.countActiveRooms()) }
    }
    monitor.subscribe(ApplicationStopped) {
        sweeper.cancel()
    }
}
