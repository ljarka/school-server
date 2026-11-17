package school.journal

import org.jsoup.Jsoup
import school.model.AuthRejected
import school.model.JournalUnavailable

internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/62.0"

internal fun HttpResponseData.isAuthError(): Boolean = code == 401 || code == 403

/** A 403 that says "this account may not see this resource", as opposed to an expired session. */
internal fun HttpResponseData.isAccessDenied(): Boolean = code == 403 && body.contains("\"AccessDeny\"")

/** A 401 that says the token lacks scopes for an optional module, rather than an expired session. */
internal fun HttpResponseData.isInsufficientScopes(): Boolean =
    code == 401 && body.contains("insufficient scopes", ignoreCase = true)

/** A 401 insufficient scopes, 403 AccessDeny, 404 NotFound or 405 MethodNotAllowed for an optional module. */
internal fun HttpResponseData.isOptionalModuleUnavailable(): Boolean =
    isInsufficientScopes() || isAccessDenied() || code == 404 || code == 405

/**
 * Returns the body of a 2xx response. 401/403 call [onAuthError]; anything else throws
 * `JournalUnavailable("http_<code>")`.
 */
internal inline fun HttpResponseData.requireSuccess(onAuthError: () -> Nothing): String {
    if (code in 200..299) return body
    if (isAuthError()) onAuthError()
    throw JournalUnavailable("http_$code")
}

/** Like [requireSuccess], but 401/403 mean the credentials were rejected. */
internal fun HttpResponseData.requireLoginStep(): String {
    val ok = requireSuccess { throw AuthRejected("rejected") }
    if (InteractiveStep.isRequired(ok)) throw AuthRejected("interactive_step_required")
    return ok
}

/** Like [requireSuccess], but 401/403 mean the session could not be renewed (temporary). */
internal fun HttpResponseData.requireRenewedSession(): String =
    requireSuccess { throw JournalUnavailable("auth_refresh_failed") }

/**
 * Detects pages that need a human (captcha, one-time code). Looks at concrete form elements and JSON keys
 * instead of any occurrence of words like "2fa", which also appear in ordinary links and script names.
 */
internal object InteractiveStep {
    private val htmlSelectors = listOf(
        ".g-recaptcha",
        ".h-captcha",
        "[data-sitekey]",
        "iframe[src*=recaptcha]",
        "iframe[src*=hcaptcha]",
        "input[name=verification_code]",
        "input[name=otp]",
        "input[name=totp]",
        "input[autocomplete=one-time-code]",
    ).joinToString(", ")

    private val jsonMarkers = listOf(
        Regex("\"verification_code\"\\s*:"),
        Regex("\"(two_factor|twoFactor|2fa)(_required|Required)?\"\\s*:\\s*true"),
        Regex("\"captcha(_required|Required)?\"\\s*:\\s*true"),
    )

    fun isRequired(body: String): Boolean {
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> jsonMarkers.any { it.containsMatchIn(trimmed) }
            trimmed.startsWith("<") -> Jsoup.parse(body).select(htmlSelectors).isNotEmpty()
            else -> false
        }
    }
}

/** Opens the messages service session from the current gateway cookies. */
internal fun PortalSession.activateMessagesService(endpoints: JournalEndpoints) {
    val res = get(
        url = endpoints.gatewayUrl(ProtocolPaths.MESSAGES_ACTIVATION_PATH),
        headers = mapOf("User-Agent" to BROWSER_USER_AGENT),
    )
    if (res.code !in 200..299) throw JournalUnavailable("http_${res.code}")
}

/**
 * How the client authenticates. Portal accounts (email login) use bearer tokens per child; school-issued
 * logins use the gateway cookie session (ADR 0012).
 */
internal interface JournalAuth {
    fun login()

    fun listChildren(): List<school.model.ChildAccount>

    /** GET on the JSON API, re-authenticating and retrying once when the session expired. */
    fun getApi(path: String, childId: String?): HttpResponseData

    /** GET on an optional JSON API resource without triggering a session refresh when it returns 401/403. */
    fun getOptionalApi(path: String, childId: String?): HttpResponseData = getApi(path, childId)

    /** GET on the gateway API (`/gateway/api/2.0<path>`), activating the child gateway session if needed. */
    fun getGatewayApi(path: String, childId: String?): HttpResponseData = getOptionalApi(path, childId)

    /** POST JSON on the gateway API (`/gateway/api/2.0<path>`), activating the child gateway session if needed. */
    fun postGatewayApiJson(path: String, jsonBody: String, childId: String?): HttpResponseData

    /** Makes sure the messages service session belongs to [childId]. */
    fun ensureMessagesSession(childId: String?)

    /** Re-authenticates after the messages service returned 401/403. */
    fun renewMessagesSession(childId: String?)
}
