package org.mpmc.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import org.mpmc.protocol.ApproveResponse
import org.mpmc.protocol.B64Url
import org.mpmc.protocol.ChallengeRequest
import org.mpmc.protocol.ChallengeResponse
import org.mpmc.protocol.CreateRoomRequest
import org.mpmc.protocol.CreateRoomResponse
import org.mpmc.protocol.JoinRoomRequest
import org.mpmc.protocol.JoinRoomResponse
import org.mpmc.protocol.JoinState
import org.mpmc.protocol.JoinStatusResponse
import org.mpmc.protocol.JwksResponse
import org.mpmc.protocol.KickRequest
import org.mpmc.protocol.ProblemResponse
import org.mpmc.protocol.RejectRequest
import org.mpmc.protocol.RoomInfoResponse
import org.mpmc.protocol.RoomState
import org.mpmc.protocol.TokenRequest
import org.mpmc.protocol.TokenResponse
import org.mpmc.protocol.Tokens
import org.mpmc.protocol.WsEvent
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

class CoordinatorException(
    val code: String,
    val status: Int,
    message: String,
) : Exception(message)

object MpmcHeaders {
    const val HOST_TOKEN = "X-Mpmc-Host-Token"
    const val IROH_ENDPOINT_ID = "X-Iroh-Endpoint-Id"
}

class CoordinatorClient(
    private val baseUrl: String,
    private val identity: AgentIdentity,
    private val clock: Clock = Clock.systemUTC(),
    private val http: HttpClient = defaultHttpClient(),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(CoordinatorClient::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var accessToken: String? = null

    @Volatile
    private var tokenExpiresAt: Instant = Instant.EPOCH

    val endpointId: String get() = identity.endpointId

    suspend fun authenticate() {
        val challenge = post<ChallengeResponse>(
            path = "/v1/auth/challenge",
            body = json.encodeToString(
                ChallengeRequest.serializer(),
                ChallengeRequest(endpointId, B64Url.encode(Tokens.randomBytes(16))),
            ),
        )
        val message = "mpmc-auth-v1${challenge.challengeId}${challenge.nonce}".toByteArray(Charsets.UTF_8)
        val signature = B64Url.encode(identity.sign(message))
        val token = post<TokenResponse>(
            path = "/v1/auth/token",
            body = json.encodeToString(
                TokenRequest.serializer(),
                TokenRequest(challenge.challengeId, signature),
            ),
        )
        accessToken = token.accessToken
        tokenExpiresAt = runCatching { Instant.parse(token.expiresAt) }.getOrDefault(Instant.EPOCH)
        log.info("coordinator_authenticated endpoint=${endpointId.take(8)}…")
    }

    suspend fun createRoom(request: CreateRoomRequest): CreateRoomResponse =
        authorized { bearer ->
            post("/v1/rooms", json.encodeToString(CreateRoomRequest.serializer(), request), bearer = bearer)
        }

    suspend fun roomInfo(code: String): RoomInfoResponse = authorized { bearer ->
        get("/v1/rooms/$code", bearer)
    }

    suspend fun heartbeat(code: String, hostToken: String) {
        val response = http.request(baseUrl + "/v1/rooms/$code/heartbeat") {
            method = HttpMethod.Post
            header(MpmcHeaders.HOST_TOKEN, hostToken)
        }
        ensureSuccess(response)
    }

    suspend fun closeRoom(code: String, hostToken: String) {
        val response = http.request(baseUrl + "/v1/rooms/$code") {
            method = HttpMethod.Delete
            header(MpmcHeaders.HOST_TOKEN, hostToken)
        }
        if (response.status.value != 204 && response.status.value != 404 && response.status.value != 410) {
            ensureSuccess(response)
        }
    }

    suspend fun joinRoom(code: String, inviteToken: String?): JoinRoomResponse = authorized { bearer ->
        post(
            "/v1/rooms/$code/join",
            json.encodeToString(JoinRoomRequest.serializer(), JoinRoomRequest(inviteToken = inviteToken)),
            bearer = bearer,
        )
    }

    suspend fun joinStatus(joinRequestId: String): JoinStatusResponse = authorized { bearer ->
        get("/v1/join-requests/$joinRequestId", bearer)
    }

    suspend fun approve(joinRequestId: String, hostToken: String): ApproveResponse {
        val response = http.request(baseUrl + "/v1/join-requests/$joinRequestId/approve") {
            method = HttpMethod.Post
            header(MpmcHeaders.HOST_TOKEN, hostToken)
        }
        ensureSuccess(response)
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun reject(joinRequestId: String, hostToken: String, reason: String?) {
        val response = http.request(baseUrl + "/v1/join-requests/$joinRequestId/reject") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RejectRequest.serializer(), RejectRequest(reason)))
            header(MpmcHeaders.HOST_TOKEN, hostToken)
        }
        ensureSuccess(response)
    }

    suspend fun kick(code: String, endpointId: String, hostToken: String) {
        val response = http.request(baseUrl + "/v1/rooms/$code/kick") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(KickRequest.serializer(), KickRequest(endpointId)))
            header(MpmcHeaders.HOST_TOKEN, hostToken)
        }
        ensureSuccess(response)
    }

    suspend fun awaitApproval(joinRequestId: String, timeoutMs: Long = 5 * 60_000L, intervalMs: Long = 2_000L): String {
        val deadline = clock.millis() + timeoutMs
        while (clock.millis() < deadline) {
            val status = joinStatus(joinRequestId)
            val ticket = status.ticket
            when (status.state) {
                JoinState.APPROVED -> if (ticket != null) return ticket
                JoinState.REJECTED -> throw CoordinatorException("JOIN_REJECTED", 403, "host đã từ chối yêu cầu vào phòng")
                JoinState.EXPIRED -> throw CoordinatorException("JOIN_EXPIRED", 410, "yêu cầu vào phòng đã hết hạn")
            }
            delay(intervalMs)
        }
        throw CoordinatorException("JOIN_TIMEOUT", 408, "hết thời gian chờ host duyệt")
    }

    suspend fun jwksRawKeys(): List<ByteArray> {
        val jwks: JwksResponse = get("/v1/.well-known/mpmc-keys", bearer = null)
        return jwks.keys.map { B64Url.decode(it.x) }
    }

    fun events(): Flow<WsEvent> = channelFlow {
        ensureToken()
        val token = accessToken ?: error("chưa authenticate")
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/v1/events"
        http.webSocket(
            urlString = wsUrl,
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    runCatching { json.decodeFromString(WsEvent.serializer(), text) }.getOrNull()?.let { send(it) }
                }
            }
        }
        awaitClose { }
    }

    fun roomClosed(info: RoomInfoResponse): Boolean = info.state != RoomState.ACTIVE

    override fun close() {
        http.close()
    }

    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        ensureToken()
        return try {
            block(accessToken ?: error("chưa authenticate"))
        } catch (e: CoordinatorException) {
            if (e.status == 401) {
                authenticate()
                block(accessToken ?: error("chưa authenticate"))
            } else {
                throw e
            }
        }
    }

    private suspend fun ensureToken() {
        val token = accessToken
        if (token == null || clock.instant().isAfter(tokenExpiresAt.minusSeconds(60))) {
            authenticate()
        }
    }

    private suspend inline fun <reified T> get(path: String, bearer: String?): T {
        val response = http.request(baseUrl + path) {
            method = HttpMethod.Get
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
        }
        ensureSuccess(response)
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend inline fun <reified T> post(path: String, body: String, bearer: String? = null): T {
        val response = http.request(baseUrl + path) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
        }
        ensureSuccess(response)
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        val status = response.status.value
        if (status in 200..299) return
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val problem = runCatching { json.decodeFromString(ProblemResponse.serializer(), text) }.getOrNull()
        throw CoordinatorException(
            code = problem?.code ?: "HTTP_$status",
            status = status,
            message = problem?.detail ?: text.take(200),
        )
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(WebSockets)
        }
    }
}
