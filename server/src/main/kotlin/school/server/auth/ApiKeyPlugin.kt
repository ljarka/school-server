package school.server.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import school.server.api.ApiErrorResponse
import school.server.api.ErrorBody
import school.server.config.Secret
import school.server.config.TrustProxy

class ApiKeyPluginConfig {
    var apiKey: Secret? = null
    var trustProxy: TrustProxy = TrustProxy.NONE
    var limiter: WrongKeyLimiter = WrongKeyLimiter()
}

private val logger = LoggerFactory.getLogger("school.server.auth.ApiKeyPlugin")

val ApiKeyPlugin =
    createRouteScopedPlugin("ApiKeyPlugin", ::ApiKeyPluginConfig) {
        val expectedBytes = pluginConfig.apiKey?.reveal()?.toByteArray(Charsets.UTF_8)
        val trustProxy = pluginConfig.trustProxy
        val limiter = pluginConfig.limiter

        onCall { call ->
            val address = clientAddress(call, trustProxy)
            val retryAfter = limiter.blockedRetryAfterSeconds(address)
            if (retryAfter != null) {
                call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiErrorResponse(
                        ErrorBody(
                            code = "TOO_MANY_WRONG_KEYS",
                            message = "Too many invalid API key attempts. Try again later.",
                        ),
                    ),
                )
                return@onCall
            }

            if (expectedBytes == null) {
                return@onCall
            }

            val supplied = call.request.headers["X-School-Key"]
            val valid = supplied != null &&
                MessageDigest.isEqual(expectedBytes, supplied.toByteArray(Charsets.UTF_8))

            if (!valid) {
                limiter.recordWrongKey(address)
                logger.warn("Rejected request with invalid or missing API key from {}", address)
                call.response.header(HttpHeaders.WWWAuthenticate, "School-Key")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(
                        ErrorBody(
                            code = "UNAUTHORIZED",
                            message = "Valid X-School-Key header is required.",
                        ),
                    ),
                )
                return@onCall
            }
        }
    }
