package org.mpmc.coordinator.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import org.mpmc.protocol.ProblemResponse

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    detail: String,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(detail) {
    companion object {
        fun badRequest(code: String, detail: String) = ApiException(HttpStatusCode.BadRequest, code, detail)
        fun unauthorized(detail: String) = ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", detail)
        fun forbidden(detail: String) = ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", detail)
        fun notFound(code: String, detail: String) = ApiException(HttpStatusCode.NotFound, code, detail)
        fun conflict(code: String, detail: String) = ApiException(HttpStatusCode.Conflict, code, detail)
        fun tooManyRequests(retryAfterSeconds: Long) = ApiException(
            status = HttpStatusCode.TooManyRequests,
            code = "RATE_LIMITED",
            detail = "Rate limit exceeded",
            headers = mapOf(HttpHeaders.RetryAfter to retryAfterSeconds.toString()),
        )
    }
}

private val problemJson = Json { encodeDefaults = true }

fun problemOf(status: HttpStatusCode, code: String, detail: String): ProblemResponse = ProblemResponse(
    type = "https://mpmc.dev/problems/${code.lowercase()}",
    title = status.description,
    status = status.value,
    detail = detail,
    code = code,
)

suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    code: String,
    detail: String,
    headers: Map<String, String> = emptyMap(),
) {
    headers.forEach { (name, value) -> response.headers.append(name, value) }
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText(
        text = problemJson.encodeToString(ProblemResponse.serializer(), problemOf(status, code, detail)),
        contentType = ContentType("application", "problem+json"),
        status = status,
    )
}
