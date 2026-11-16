package school.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ErrorBody(val code: String, val message: String)

@Serializable
data class ApiErrorResponse(val error: ErrorBody)

class ApiException(val status: HttpStatusCode, val code: String, override val message: String) :
    RuntimeException(message)

private val logger = LoggerFactory.getLogger("school.server.api.Errors")

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                cause.status,
                ApiErrorResponse(ErrorBody(code = cause.code, message = cause.message)),
            )
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse(ErrorBody(code = "INTERNAL_ERROR", message = "Internal server error")),
            )
        }
    }
}
