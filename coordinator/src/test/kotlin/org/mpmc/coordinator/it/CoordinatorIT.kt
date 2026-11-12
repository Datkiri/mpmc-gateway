package org.mpmc.coordinator.it

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mpmc.coordinator.config.CoordinatorConfig
import org.mpmc.coordinator.http.MpmcHeaders
import org.mpmc.coordinator.module
import org.mpmc.coordinator.store.Database
import org.mpmc.protocol.ApprovalMode
import org.mpmc.protocol.ApproveResponse
import org.mpmc.protocol.B64Url
import org.mpmc.protocol.ChallengeRequest
import org.mpmc.protocol.ChallengeResponse
import org.mpmc.protocol.CreateRoomRequest
import org.mpmc.protocol.CreateRoomResponse
import org.mpmc.protocol.Digest
import org.mpmc.protocol.Ed25519
import org.mpmc.protocol.JoinRoomRequest
import org.mpmc.protocol.JoinRoomResponse
import org.mpmc.protocol.JoinState
import org.mpmc.protocol.JoinStatusResponse
import org.mpmc.protocol.JwksResponse
import org.mpmc.protocol.KickRequest
import org.mpmc.protocol.RoomInfoResponse
import org.mpmc.protocol.RoomState
import org.mpmc.protocol.TicketCodec
import org.mpmc.protocol.TicketFailure
import org.mpmc.protocol.TicketPayload
import org.mpmc.protocol.TicketResult
import org.mpmc.protocol.TokenRequest
import org.mpmc.protocol.TokenResponse
import org.mpmc.protocol.Tokens
import org.mpmc.protocol.WsEvent
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class CoordinatorIT {
    private val testJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private data class Identity(val pair: KeyPair, val endpointId: String, val token: String)

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    companion object {
        private const val RELAY_TOKEN = "test-relay-callback-token-0123456789"

        private lateinit var database: Database
        private lateinit var keys: org.mpmc.coordinator.auth.SigningKeys
        private lateinit var config: CoordinatorConfig

        @JvmStatic
        @BeforeAll
        fun setUp() {
            database = Database.connect(PgSupport.jdbcUrl, PgSupport.user, PgSupport.password, poolSize = 6)
            database.migrate()
            val keyDir = Path.of(System.getProperty("java.io.tmpdir"), "mpmc-it-${UUID.randomUUID()}")
            keys = org.mpmc.coordinator.auth.SigningKeys.loadOrCreate(keyDir.resolve("signing.key"))
            config = CoordinatorConfig(
                host = "127.0.0.1",
                port = 8080,
                databaseUrl = PgSupport.jdbcUrl,
                databaseUser = PgSupport.user,
                databasePassword = PgSupport.password,
                signingKeyPath = keyDir.resolve("signing.key"),
                relayCallbackToken = RELAY_TOKEN,
                relayUrls = listOf("https://relay.test/"),
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            database.close()
        }
    }

    private fun withApp(
        clock: Clock = Clock.systemUTC(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application { module(config, database, keys, clock) }
        val client = createClient {
            install(ContentNegotiation) { json(testJson) }
            install(WebSockets)
        }
        try {
            block(client)
        } finally {
            client.close()
        }
    }

    private inline fun <reified T> jsonBody(value: T): String = testJson.encodeToString(value)

    private suspend inline fun <reified T> HttpResponse.decode(): T = testJson.decodeFromString(bodyAsText())

    private fun bearer(token: String): String = "Bearer $token"

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
        accessToken: String? = null,
        hostToken: String? = null,
    ): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        accessToken?.let { header(HttpHeaders.Authorization, bearer(it)) }
        hostToken?.let { header(MpmcHeaders.HOST_TOKEN, it) }
        setBody(TextContent(body, ContentType.Application.Json))
    }

    private suspend fun HttpClient.getJson(path: String, accessToken: String): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, bearer(accessToken)) }

    private suspend fun HttpClient.register(): Identity {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val endpointId = Digest.hex(Ed25519.rawFromPublic(pair.public))
        val challengeResponse = postJson(
            "/v1/auth/challenge",
            jsonBody(ChallengeRequest(endpointId, B64Url.encode(Tokens.randomBytes(16)))),
        )
        assertEquals(200, challengeResponse.status.value, challengeResponse.bodyAsText())
        val challenge: ChallengeResponse = challengeResponse.decode()

        val message = "mpmc-auth-v1${challenge.challengeId}${challenge.nonce}".toByteArray(Charsets.UTF_8)
        val signature = B64Url.encode(Ed25519.sign(pair.private, message))
        val tokenResponse = postJson("/v1/auth/token", jsonBody(TokenRequest(challenge.challengeId, signature)))
        assertEquals(200, tokenResponse.status.value, tokenResponse.bodyAsText())
        val token: TokenResponse = tokenResponse.decode()
        return Identity(pair, endpointId, token.accessToken)
    }

    private suspend fun HttpClient.createRoom(
        host: Identity,
        approvalMode: String = ApprovalMode.PROMPT,
        maxMembers: Int = 8,
    ): CreateRoomResponse {
        val response = postJson(
            "/v1/rooms",
            jsonBody(CreateRoomRequest(maxMembers = maxMembers, approvalMode = approvalMode, ttlMinutes = 120)),
            accessToken = host.token,
        )
        assertEquals(201, response.status.value, response.bodyAsText())
        return response.decode()
    }

    private suspend fun HttpClient.join(
        code: String,
        joiner: Identity,
        inviteToken: String? = null,
    ): HttpResponse = postJson(
        "/v1/rooms/$code/join",
        jsonBody(JoinRoomRequest(inviteToken = inviteToken, message = null)),
        accessToken = joiner.token,
    )

    private suspend fun HttpClient.jwksRaw(): ByteArray =
        B64Url.decode(get("/v1/.well-known/mpmc-keys").decode<JwksResponse>().keys.single().x)

    private suspend fun HttpClient.verifyTicket(
        ticket: String,
        room: CreateRoomResponse,
        expectedSub: String,
        at: Instant = Instant.now(),
    ): TicketPayload {
        val raw = jwksRaw()
        val result = TicketCodec.verify(
            ticket = ticket,
            verifySignature = { message, signature -> Ed25519.verify(raw, message, signature) },
            nowEpochSeconds = at.epochSecond,
            expectedRoomId = room.roomId,
            expectedEndpointId = expectedSub,
        )
        return assertInstanceOf(TicketResult.Valid::class.java, result).payload
    }

    private fun <T> withDb(block: (Connection) -> T): T =
        DriverManager.getConnection(PgSupport.jdbcUrl, PgSupport.user, PgSupport.password).use(block)

    @Test
    fun `create room returns code and tokens while db stores hashes only`() = withApp { client ->
        val host = client.register()
        val room = client.createRoom(host)

        assertEquals(8, room.code.length)
        assertTrue(room.code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        assertEquals(43, room.inviteToken.length)
        assertEquals(43, room.hostToken.length)
        assertNotEquals(room.inviteToken, room.hostToken)
        assertEquals("ok", client.get("/healthz").bodyAsText())

        withDb { connection ->
            connection.prepareStatement("select host_token_hash, invite_token_hash from rooms where room_id = ?::uuid")
                .use { statement ->
                    statement.setString(1, room.roomId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        val hostHash = rs.getBytes(1)
                        val inviteHash = rs.getBytes(2)
                        assertArrayEquals(Digest.sha256(room.hostToken), hostHash)
                        assertArrayEquals(Digest.sha256(room.inviteToken), inviteHash)
                        assertNotEquals(room.hostToken, String(hostHash, Charsets.UTF_8))
                        assertNotEquals(room.inviteToken, String(inviteHash, Charsets.UTF_8))
                    }
                }
        }
    }

    @Test
    fun `join with correct invite auto approves and ticket verifies via jwks`() = withApp { client ->
        val host = client.register()
        val room = client.createRoom(host, ApprovalMode.INVITE)
        val joiner = client.register()

        val response = client.join(room.code, joiner, room.inviteToken)
        assertEquals(200, response.status.value, response.bodyAsText())
        val join: JoinRoomResponse = response.decode()
        assertEquals(JoinState.APPROVED, join.state)
        val ticket = requireNotNull(join.ticket)

        val payload = client.verifyTicket(ticket, room, joiner.endpointId)
        assertEquals(room.roomId, payload.roomId)
        assertEquals(room.code, payload.code)
        assertEquals(4, payload.maxStreams)
        assertEquals(120L, payload.exp - payload.iat)
        assertEquals("guest", payload.role)

        val status: JoinStatusResponse =
            client.getJson("/v1/join-requests/${join.joinRequestId}", joiner.token).decode()
        assertEquals(JoinState.APPROVED, status.state)
        assertNotNull(status.ticket)

        withDb { connection ->
            connection.prepareStatement("select count(*) from tickets where room_id = ?::uuid and endpoint_id = ?")
                .use { statement ->
                    statement.setString(1, room.roomId)
                    statement.setString(2, joiner.endpointId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
        }
    }

    @Test
    fun `wrong invite stays pending and host approval issues ticket`() = withApp { client ->
        val host = client.register()
        val room = client.createRoom(host, ApprovalMode.INVITE)
        val joiner = client.register()

        val response = client.join(room.code, joiner, "wrong-invite-token")
        assertEquals(200, response.status.value, response.bodyAsText())
        val join: JoinRoomResponse = response.decode()
        assertEquals(JoinState.PENDING, join.state)
        assertNull(join.ticket)

        val pending: JoinStatusResponse =
            client.getJson("/v1/join-requests/${join.joinRequestId}", joiner.token).decode()
        assertEquals(JoinState.PENDING, pending.state)

        val approvedResponse = client.postJson(
            "/v1/join-requests/${join.joinRequestId}/approve",
            "",
            hostToken = room.hostToken,
        )
        assertEquals(200, approvedResponse.status.value, approvedResponse.bodyAsText())
        val approved: ApproveResponse = approvedResponse.decode()
        client.verifyTicket(approved.ticket, room, joiner.endpointId)

        val after: JoinStatusResponse =
            client.getJson("/v1/join-requests/${join.joinRequestId}", joiner.token).decode()
        assertEquals(JoinState.APPROVED, after.state)
        assertNotNull(after.ticket)
    }

    @Test
    fun `wrong host token is forbidden`() = withApp { client ->
        val host = client.register()
        val room = client.createRoom(host)
        val joiner = client.register()
        val join: JoinRoomResponse = client.join(room.code, joiner).decode()
        val wrong = "not-the-host-token"

        assertEquals(
            403,
            client.postJson("/v1/rooms/${room.code}/heartbeat", "", hostToken = wrong).status.value,
        )
        assertEquals(
            403,
            client.delete("/v1/rooms/${room.code}") { header(MpmcHeaders.HOST_TOKEN, wrong) }.status.value,
        )
        assertEquals(
            403,
            client.postJson(
                "/v1/rooms/${room.code}/kick",
                jsonBody(KickRequest(joiner.endpointId)),
                hostToken = wrong,
            ).status.value,
        )
        assertEquals(
            403,
            client.postJson("/v1/join-requests/${join.joinRequestId}/approve", "", hostToken = wrong).status.value,
        )
        assertEquals(
            204,
            client.postJson("/v1/rooms/${room.code}/heartbeat", "", hostToken = room.hostToken).status.value,
        )
    }

    @Test
    fun `sixth join attempt in a minute is rate limited`() = withApp { client ->
        val host = client.register()
        val room = client.createRoom(host)
        val joiner = client.register()

        repeat(5) {
            val response = client.join(room.code, joiner)
            assertEquals(200, response.status.value, response.bodyAsText())
        }
        val limited = client.join(room.code, joiner)
        assertEquals(429, limited.status.value)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `ticket expires after 120 seconds and join status reissues`() {
        val mutable = MutableClock(Instant.parse("2026-02-01T00:00:00Z"))
        withApp(mutable) { client ->
            val host = client.register()
            val room = client.createRoom(host, ApprovalMode.INVITE)
            val joiner = client.register()
            val join: JoinRoomResponse = client.join(room.code, joiner, room.inviteToken).decode()
            val first = requireNotNull(join.ticket)

            val payload = client.verifyTicket(first, room, joiner.endpointId, mutable.instant())
            assertEquals(120L, payload.exp - payload.iat)

            val raw = client.jwksRaw()
            val expired = TicketCodec.verify(
                ticket = first,
                verifySignature = { message, signature -> Ed25519.verify(raw, message, signature) },
                nowEpochSeconds = mutable.instant().plusSeconds(120).epochSecond,
                expectedRoomId = room.roomId,
                expectedEndpointId = joiner.endpointId,
            )
            assertEquals(TicketResult.Invalid(TicketFailure.EXPIRED), expired)

            repeat(4) {
                mutable.advanceSeconds(31)
                val heartbeat = client.postJson(
                    "/v1/rooms/${room.code}/heartbeat",
                    "",
                    hostToken = room.hostToken,
                )
                assertEquals(204, heartbeat.status.value, heartbeat.bodyAsText())
            }

            val status: JoinStatusResponse =
                client.getJson("/v1/join-requests/${join.joinRequestId}", joiner.token).decode()
            assertEquals(JoinState.APPROVED, status.state)
            val second = requireNotNull(status.ticket)
            assertNotEquals(first, second)
            val payload2 = client.verifyTicket(second, room, joiner.endpointId, mutable.instant())
            assertEquals(mutable.instant().epochSecond, payload2.iat)
            assertEquals(120L, payload2.exp - payload2.iat)
        }
    }

    @Test
    fun `relay authorize allows only valid endpoints`() = withApp { client ->
        val host = client.register()
        val room = client.createRoom(host, ApprovalMode.INVITE)
        val joiner = client.register()
        client.join(room.code, joiner, room.inviteToken)
        val stranger = client.register()

        suspend fun authorize(method: HttpMethod, endpointId: String, token: String): HttpResponse =
            client.request("/v1/relay/authorize") {
                this.method = method
                header(MpmcHeaders.IROH_ENDPOINT_ID, endpointId)
                header(HttpHeaders.Authorization, bearer(token))
            }

        val memberResult = authorize(HttpMethod.Post, joiner.endpointId, RELAY_TOKEN)
        assertEquals(HttpStatusCode.OK, memberResult.status)
        assertEquals("true", memberResult.bodyAsText())

        val hostResult = authorize(HttpMethod.Get, host.endpointId, RELAY_TOKEN)
        assertEquals(HttpStatusCode.OK, hostResult.status)
        assertEquals("true", hostResult.bodyAsText())

        val wrongToken = authorize(HttpMethod.Post, joiner.endpointId, "wrong-callback-token")
        assertEquals(HttpStatusCode.Forbidden, wrongToken.status)
        assertEquals("false", wrongToken.bodyAsText())

        val strangerResult = authorize(HttpMethod.Post, stranger.endpointId, RELAY_TOKEN)
        assertEquals(HttpStatusCode.Forbidden, strangerResult.status)

        val malformed = authorize(HttpMethod.Post, "not-an-endpoint", RELAY_TOKEN)
        assertEquals(HttpStatusCode.Forbidden, malformed.status)

        val closed = client.delete("/v1/rooms/${room.code}") {
            header(MpmcHeaders.HOST_TOKEN, room.hostToken)
        }
        assertEquals(204, closed.status.value)

        val hostAfterClose = authorize(HttpMethod.Post, host.endpointId, RELAY_TOKEN)
        assertEquals(HttpStatusCode.Forbidden, hostAfterClose.status)
    }

    @Test
    fun `secrets never appear in logs`() = withApp { client ->
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val previousLevel = root.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.level = Level.INFO
        root.addAppender(appender)

        var inviteToken = ""
        var hostToken = ""
        var ticket = ""
        var hostAccessToken = ""
        var joinerAccessToken = ""
        try {
            val host = client.register()
            hostAccessToken = host.token
            val room = client.createRoom(host, ApprovalMode.INVITE)
            inviteToken = room.inviteToken
            hostToken = room.hostToken
            val joiner = client.register()
            joinerAccessToken = joiner.token
            val join: JoinRoomResponse = client.join(room.code, joiner, inviteToken).decode()
            ticket = requireNotNull(join.ticket)
            client.getJson("/v1/join-requests/${join.joinRequestId}", joiner.token)
        } finally {
            root.detachAppender(appender)
            root.level = previousLevel
        }

        val logged = appender.list.joinToString("\n") { event ->
            event.formattedMessage + " " + (event.throwableProxy?.message ?: "")
        }
        assertFalse(logged.contains(inviteToken), "invite token leaked into logs")
        assertFalse(logged.contains(hostToken), "host token leaked into logs")
        assertFalse(logged.contains(ticket), "join ticket leaked into logs")
        assertFalse(logged.contains(hostAccessToken), "host access token leaked into logs")
        assertFalse(logged.contains(joinerAccessToken), "joiner access token leaked into logs")
    }

    @Test
    fun `health metrics and room lifecycle`() = withApp { client ->
        assertEquals("ok", client.get("/healthz").bodyAsText())

        val host = client.register()
        val room = client.createRoom(host, ApprovalMode.INVITE)
        assertEquals(
            204,
            client.postJson("/v1/rooms/${room.code}/heartbeat", "", hostToken = room.hostToken).status.value,
        )
        val info: RoomInfoResponse = client.getJson("/v1/rooms/${room.code}", host.token).decode()
        assertEquals(RoomState.ACTIVE, info.state)
        assertEquals(1, info.memberCount)

        val joiner = client.register()
        val joinResponse = client.join(room.code, joiner, room.inviteToken)
        assertEquals(200, joinResponse.status.value, joinResponse.bodyAsText())

        val badTokenRequest = client.postJson(
            "/v1/auth/token",
            jsonBody(TokenRequest(UUID.randomUUID().toString(), B64Url.encode(ByteArray(64)))),
        )
        assertEquals(401, badTokenRequest.status.value)

        client.request("/v1/relay/authorize") {
            method = HttpMethod.Post
            header(MpmcHeaders.IROH_ENDPOINT_ID, joiner.endpointId)
            header(HttpHeaders.Authorization, bearer("wrong-callback-token"))
        }

        assertEquals(
            204,
            client.delete("/v1/rooms/${room.code}") { header(MpmcHeaders.HOST_TOKEN, room.hostToken) }.status.value,
        )
        assertEquals(409, client.join(room.code, joiner).status.value)

        val metrics = client.get("/metrics").bodyAsText()
        assertTrue(metrics.contains("mpmc_rooms_active"), metrics)
        assertTrue(metrics.contains("mpmc_join_requests_total{state=\"approved\"}"), metrics)
        assertTrue(metrics.contains("mpmc_auth_failures_total"), metrics)
        assertTrue(metrics.contains("mpmc_relay_auth_total{result=\"deny\"}"), metrics)
        assertTrue(metrics.contains("mpmc_http_requests_total{route=\"rooms.create\",status=\"201\"}"), metrics)
        assertTrue(metrics.contains("mpmc_ticket_issued_total"), metrics)
    }

    @Test
    fun `websocket events require bearer and deliver room events`() = withApp { client ->
        val host = client.register()
        val actor = createClient {
            install(ContentNegotiation) { json(testJson) }
        }
        try {
            val unauthorized = runCatching { client.webSocket("/v1/events") {} }
            assertTrue(unauthorized.isFailure, "websocket without bearer should fail")

            client.webSocket(
                "/v1/events",
                request = { header(HttpHeaders.Authorization, bearer(host.token)) },
            ) {
                val response = actor.postJson(
                    "/v1/rooms",
                    jsonBody(CreateRoomRequest()),
                    accessToken = host.token,
                )
                assertEquals(201, response.status.value, response.bodyAsText())

                val frame = withTimeout(10_000) { incoming.receive() }
                assertTrue(frame is Frame.Text)
                val event = testJson.decodeFromString(WsEvent.serializer(), (frame as Frame.Text).readText())
                assertEquals("room.created", event.type)
                assertNotNull(event.roomId)
            }
        } finally {
            actor.close()
        }
    }
}
