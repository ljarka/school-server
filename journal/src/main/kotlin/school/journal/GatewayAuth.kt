package school.journal

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import school.model.AuthRejected
import school.model.ChildAccount
import school.model.JournalUnavailable

/** Cookie-session login for school-issued (non-email) logins (ADR 0012). */
internal class GatewayAuth(
    private val session: PortalSession,
    private val endpoints: JournalEndpoints,
    private val login: String,
    private val passwordProvider: () -> String,
    private val json: Json,
) : JournalAuth {
    private val logger = LoggerFactory.getLogger(GatewayAuth::class.java)

    @Volatile
    private var messagesActivated = false

    override fun login() {
        // Steps before the password is posted: a 401/403 here is not a credentials verdict
        // (e.g. a WAF or maintenance page), so it must stay retryable.
        val initUrl = endpoints.gatewayUrl(
            "${ProtocolPaths.GATEWAY_PORTAL_INIT_PATH}?v=${System.currentTimeMillis() / 1000}",
        )
        requirePreLoginStep(session.get(url = initUrl, headers = userAgent()))

        val authUrl = endpoints.apiOriginUrl(ProtocolPaths.OAUTH_AUTHORIZATION_PATH)
        requirePreLoginStep(session.get(url = authUrl, headers = userAgent()))

        val formBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "login")
            .addFormDataPart("login", login)
            .addFormDataPart("pass", passwordProvider())
            .build()

        val postRes = session.postForm(
            url = authUrl,
            body = formBody,
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to BROWSER_USER_AGENT,
                "X-Requested-With" to "XMLHttpRequest",
                "X-Baner" to createBanerHeader(),
                "Referer" to authUrl,
            ),
        )
        if (postRes.code >= 500) throw JournalUnavailable("http_${postRes.code}")
        if (postRes.code in REJECTED_CODES) throw AuthRejected("rejected")
        if (postRes.code !in 200..299) throw JournalUnavailable("http_${postRes.code}")
        if (postRes.body.contains("\"status\":\"error\"", ignoreCase = true) ||
            postRes.body.contains("\"errors\"", ignoreCase = true)
        ) {
            throw AuthRejected("rejected")
        }
        if (InteractiveStep.isRequired(postRes.body)) throw AuthRejected("interactive_step_required")

        // Steps after the password was accepted: a 401/403 means the login did not really succeed.
        session.get(url = endpoints.apiOriginUrl(ProtocolPaths.OAUTH_2FA_PATH), headers = userAgent())
            .requireLoginStep()

        val tokenInfo = session.get(
            url = endpoints.gatewayUrl(ProtocolPaths.GATEWAY_TOKEN_INFO_PATH),
            headers = jsonHeaders(),
        ).requireLoginStep()
        val identifier = runCatching {
            json.decodeFromString<GatewayTokenInfoDto>(tokenInfo).UserIdentifier
        }.getOrDefault("")
        if (identifier.isNotEmpty()) {
            session.get(
                url = endpoints.gatewayUrl("${ProtocolPaths.GATEWAY_USER_INFO_PATH}/$identifier"),
                headers = jsonHeaders(),
            ).requireLoginStep()
        }
        messagesActivated = false
    }

    override fun listChildren(): List<ChildAccount> = listOf(ChildAccount(id = login, login = login, name = login))

    override fun getApi(path: String, childId: String?): HttpResponseData {
        val url = endpoints.gatewayUrl("${ProtocolPaths.GATEWAY_API_PREFIX}$path")
        val first = session.get(url = url, headers = jsonHeaders())
        if (first.isOptionalModuleUnavailable() || !first.isAuthError()) {
            return first
        }
        logger.debug("e-journal gateway returned {}, re-authenticating", first.code)
        login()
        return session.get(url = url, headers = jsonHeaders())
    }

    override fun getOptionalApi(path: String, childId: String?): HttpResponseData {
        val url = endpoints.gatewayUrl("${ProtocolPaths.GATEWAY_API_PREFIX}$path")
        return session.get(url = url, headers = jsonHeaders())
    }

    override fun postGatewayApiJson(path: String, jsonBody: String, childId: String?): HttpResponseData {
        val url = endpoints.gatewayUrl("${ProtocolPaths.GATEWAY_API_PREFIX}$path")
        val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        return session.postForm(url = url, body = body, headers = jsonHeaders())
    }

    override fun ensureMessagesSession(childId: String?) {
        if (!messagesActivated) activateMessages()
    }

    override fun renewMessagesSession(childId: String?) {
        login()
        activateMessages()
    }

    private fun activateMessages() {
        session.activateMessagesService(endpoints)
        messagesActivated = true
    }

    private fun requirePreLoginStep(res: HttpResponseData) {
        if (res.code !in 200..299) throw JournalUnavailable("http_${res.code}")
        if (InteractiveStep.isRequired(res.body)) throw AuthRejected("interactive_step_required")
    }

    private fun userAgent() = mapOf("User-Agent" to BROWSER_USER_AGENT)

    private fun jsonHeaders() = mapOf("Accept" to "application/json", "User-Agent" to BROWSER_USER_AGENT)

    private fun createBanerHeader(): String {
        val ts = encodeBanerSegment(System.currentTimeMillis().toString())
        val rnd = encodeBanerSegment(Math.random().toString())
        return "${rnd}_$ts"
    }

    private fun encodeBanerSegment(value: String): String = value.map { (it.code + 20).toChar() }.joinToString("")

    private companion object {
        val REJECTED_CODES = setOf(400, 401, 403, 422)
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
