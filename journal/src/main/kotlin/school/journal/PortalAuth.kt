package school.journal

import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import school.model.AuthRejected
import school.model.ChildAccount
import school.model.JournalUnavailable

/** Parent-portal login (email) with per-child bearer tokens (ADR 0011). */
internal class PortalAuth(
    private val session: PortalSession,
    private val endpoints: JournalEndpoints,
    private val login: String,
    private val passwordProvider: () -> String,
    private val configuredChild: String?,
    private val json: Json,
) : JournalAuth {
    private val logger = LoggerFactory.getLogger(PortalAuth::class.java)
    private val tokenProvider = TokenProvider()

    @Volatile
    private var activeMessagesChildId: String? = null

    override fun login() {
        tokenProvider.setAccounts(performLogin())
        activeMessagesChildId = null
    }

    override fun listChildren(): List<ChildAccount> = tokenProvider.listAccounts().map { account ->
        ChildAccount(
            id = account.id.toString(),
            login = account.login,
            name = account.studentName.ifBlank { account.login },
        )
    }

    override fun getApi(path: String, childId: String?): HttpResponseData {
        val token = tokenProvider.currentToken(childId) ?: throw JournalUnavailable("not_authenticated")
        val first = getWithBearer(path, token)
        if (first.code != 401) {
            return first
        }
        logger.debug("e-journal API returned 401, refreshing token")
        return getWithBearer(path, refreshToken(childId))
    }

    override fun ensureMessagesSession(childId: String?) {
        val target = tokenProvider.currentAccount(childId)?.id?.toString()
        if (activeMessagesChildId == null || activeMessagesChildId != target) {
            activateMessages(childId)
        }
    }

    override fun renewMessagesSession(childId: String?) {
        refreshToken(childId)
        activateMessages(childId)
    }

    private fun getWithBearer(path: String, token: String): HttpResponseData = session.get(
        url = endpoints.apiUrl(path),
        headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token"),
    )

    private fun refreshToken(childId: String?): String = tokenProvider.refresh(
        childId = childId,
        fetchFresh = { childLogin -> fetchFreshChildAccount(childLogin) },
        relogin = { performLogin() },
    )

    private fun performLogin(): List<PortalAccountDto> {
        val loginPageUrl = endpoints.portalUrl(ProtocolPaths.PORTAL_LOGIN_PATH)
        val loginPage = session.get(loginPageUrl).requireLoginStep()

        val form = FormBody.Builder()
            .add("email", login)
            .add("password", passwordProvider())
            .add("_token", extractCsrfToken(loginPage))
            .build()

        val loginAction = session.postForm(
            url = endpoints.portalUrl(ProtocolPaths.PORTAL_LOGIN_ACTION_PATH),
            body = form,
            headers = mapOf("Referer" to loginPageUrl),
        )
        if (loginAction.code == 422) throw AuthRejected("rejected")
        loginAction.requireLoginStep()

        val me = session.get(url = endpoints.portalUrl(ProtocolPaths.PORTAL_ME_PATH), headers = acceptJson())
            .requireLoginStep()
        try {
            json.decodeFromString<PortalMeDto>(me)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:me", e)
        }
        return selectChildAccounts()
    }

    private fun selectChildAccounts(): List<PortalAccountDto> {
        val body = session.get(url = endpoints.portalUrl(ProtocolPaths.PORTAL_ACCOUNTS_PATH), headers = acceptJson())
            .requireLoginStep()
        val parsed = try {
            json.decodeFromString<PortalAccountsResponseDto>(body)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:accounts", e)
        }
        if (parsed.accounts.isEmpty()) {
            throw AuthRejected("child_not_found")
        }
        val selector = configuredChild?.trim()?.ifEmpty { null } ?: return parsed.accounts
        return listOf(ChildSelector.select(parsed.accounts, selector))
    }

    private fun fetchFreshChildAccount(childLogin: String): PortalAccountDto? {
        val res = session.get(
            url = endpoints.portalUrl("${ProtocolPaths.PORTAL_FRESH_ACCOUNT_PATH}/$childLogin"),
            headers = acceptJson(),
        )
        if (res.isAuthError()) {
            return null
        }
        val body = res.requireSuccess { throw JournalUnavailable("http_${res.code}") }
        return try {
            json.decodeFromString<PortalAccountDto>(body)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:fresh_account", e)
        }
    }

    private fun extractCsrfToken(html: String): String =
        Jsoup.parse(html).selectFirst("input[name=_token]")?.attr("value")?.trim()?.ifEmpty { null }
            ?: throw JournalUnavailable("layout_changed:login")

    private fun activateMessages(childId: String?) {
        val token = tokenProvider.currentToken(childId) ?: refreshToken(childId)
        val autoLoginRes = postAutoLoginToken(token).let { res ->
            if (res.code == 401) postAutoLoginToken(refreshToken(childId)) else res
        }
        val autoLoginBody = autoLoginRes.requireRenewedSession()
        val autoLoginToken = try {
            json.decodeFromString<AutoLoginTokenDto>(autoLoginBody).token
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:auto_login_token", e)
        }
        if (autoLoginToken.isBlank()) {
            throw JournalUnavailable("layout_changed:auto_login_token")
        }

        val encoded = URLEncoder.encode(autoLoginToken, "UTF-8")
        session.get(
            url = endpoints.gatewayUrl(
                "${ProtocolPaths.AUTO_LOGIN_GATEWAY_PREFIX}$encoded${ProtocolPaths.AUTO_LOGIN_GATEWAY_SUFFIX}",
            ),
            headers = mapOf("User-Agent" to ProtocolPaths.MOBILE_USER_AGENT),
        ).requireRenewedSession()

        session.activateMessagesService(endpoints)
        activeMessagesChildId = tokenProvider.currentAccount(childId)?.id?.toString()
    }

    private fun postAutoLoginToken(accessToken: String): HttpResponseData = session.postForm(
        url = endpoints.apiUrl(ProtocolPaths.API_AUTO_LOGIN_TOKEN_PATH),
        body = FormBody.Builder().build(),
        headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $accessToken",
            "User-Agent" to ProtocolPaths.MOBILE_USER_AGENT,
        ),
    )

    private fun acceptJson() = mapOf("Accept" to "application/json")

    @Serializable
    private data class AutoLoginTokenDto(@SerialName("Token") val token: String = "")
}
