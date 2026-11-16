package school.journal

import kotlinx.serialization.json.Json
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import school.model.AuthRejected
import school.model.Grade
import school.model.JournalClient
import school.model.JournalUnavailable
import school.model.Message
import school.model.MessageHeader

class HttpJournalClient(
    private val endpoints: JournalEndpoints,
    private val login: String,
    private val passwordProvider: () -> String,
    private val configuredChild: String? = null,
    minRequestDelayMs: Long = 500L,
) : JournalClient {
    private val logger = LoggerFactory.getLogger(HttpJournalClient::class.java)
    private val session = PortalSession(endpoints = endpoints, minDelayMs = minRequestDelayMs)
    private val tokenProvider = TokenProvider()
    private val json = Json { ignoreUnknownKeys = true }

    override fun login() {
        val account = performPortalLogin()
        tokenProvider.setAccount(account)
        logger.debug("e-journal login succeeded")
    }

    override fun fetchGrades(): List<Grade> {
        val gradesRaw = getApiJson(ProtocolPaths.API_GRADES_PATH)
        val gradesResponse = try {
            json.decodeFromString<GradesResponseDto>(gradesRaw)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:grades", e)
        }

        if (gradesResponse.grades.isEmpty()) {
            return emptyList()
        }

        val subjects = decodeLookup<SubjectsResponseDto>(
            ProtocolPaths.API_SUBJECTS_PATH,
            "subjects",
        ).subjects.associateBy { it.id }

        val categories = decodeLookup<GradeCategoriesResponseDto>(
            ProtocolPaths.API_CATEGORIES_PATH,
            "categories",
        ).categories.associateBy { it.id }

        val comments = decodeLookup<GradeCommentsResponseDto>(
            ProtocolPaths.API_COMMENTS_PATH,
            "comments",
        ).comments
            .filter { it.grade != null && it.text.isNotBlank() }
            .groupBy { it.grade!!.id }
            .mapValues { (_, list) -> list.joinToString("\n") { it.text.trim() } }

        val users = decodeLookup<UsersResponseDto>(
            ProtocolPaths.API_USERS_PATH,
            "users",
        ).users.associateBy { it.id }

        return GradesMapper.map(
            grades = gradesResponse.grades,
            subjects = subjects,
            categories = categories,
            commentsByGradeId = comments,
            users = users,
        )
    }

    private inline fun <reified T> decodeLookup(path: String, name: String): T {
        val raw = getApiJson(path)
        return try {
            json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:$name", e)
        }
    }

    override fun fetchMessageHeaders(): List<MessageHeader> =
        throw UnsupportedOperationException("Messages fetching is implemented in T-007")

    override fun fetchMessage(sourceId: String): Message =
        throw UnsupportedOperationException("Messages fetching is implemented in T-007")

    /**
     * Performs a GET request to JSON API v3 with the cached child bearer token,
     * refreshing once on 401.
     */
    internal fun getApiJson(path: String): String {
        val token = tokenProvider.currentToken()
            ?: throw JournalUnavailable("not_authenticated")

        val first = session.get(
            url = endpoints.apiUrl(path),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $token",
            ),
        )

        if (first.code == 401) {
            logger.debug("e-journal API returned 401, refreshing token")
            val refreshedToken = tokenProvider.refresh(
                fetchFresh = { childLogin -> fetchFreshChildAccount(childLogin) },
                relogin = { performPortalLogin() },
            )
            val retry = session.get(
                url = endpoints.apiUrl(path),
                headers = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer $refreshedToken",
                ),
            )
            return handleApiResponse(retry, path)
        }

        return handleApiResponse(first, path)
    }

    private fun handleApiResponse(response: HttpResponseData, path: String): String {
        if (response.code == 401 || response.code == 403) {
            throw JournalUnavailable("auth_refresh_failed")
        }
        if (response.code >= 500) {
            throw JournalUnavailable("http_${response.code}")
        }
        if (response.code !in 200..299) {
            throw JournalUnavailable("http_${response.code}_$path")
        }
        return response.body
    }

    private fun performPortalLogin(): PortalAccountDto {
        val loginPageUrl = endpoints.portalUrl(ProtocolPaths.PORTAL_LOGIN_PATH)
        val loginPage = session.get(loginPageUrl)

        if (loginPage.code >= 500) {
            throw JournalUnavailable("http_${loginPage.code}")
        }
        if (loginPage.code == 401 || loginPage.code == 403) {
            throw AuthRejected("rejected")
        }
        if (loginPage.code !in 200..299) {
            throw JournalUnavailable("http_${loginPage.code}")
        }

        val csrfToken = extractCsrfToken(loginPage.body)
        val form = FormBody.Builder()
            .add("email", login)
            .add("password", passwordProvider())
            .add("_token", csrfToken)
            .build()

        val loginAction = session.postForm(
            url = endpoints.portalUrl(ProtocolPaths.PORTAL_LOGIN_ACTION_PATH),
            body = form,
            headers = mapOf("Referer" to loginPageUrl),
        )

        if (loginAction.code >= 500) {
            throw JournalUnavailable("http_${loginAction.code}")
        }
        if (loginAction.code == 401 || loginAction.code == 403 || loginAction.code == 422) {
            throw AuthRejected("rejected")
        }
        if (loginAction.code !in 200..299) {
            throw JournalUnavailable("http_${loginAction.code}")
        }
        if (hasInteractiveStep(loginAction.body)) {
            throw AuthRejected("interactive_step_required")
        }

        verifyPortalSession()
        return selectChildAccount()
    }

    private fun verifyPortalSession() {
        val me = session.get(
            url = endpoints.portalUrl(ProtocolPaths.PORTAL_ME_PATH),
            headers = mapOf("Accept" to "application/json"),
        )
        if (me.code >= 500) {
            throw JournalUnavailable("http_${me.code}")
        }
        if (me.code == 401 || me.code == 403) {
            throw AuthRejected("rejected")
        }
        if (me.code !in 200..299) {
            throw JournalUnavailable("http_${me.code}")
        }
        if (hasInteractiveStep(me.body)) {
            throw AuthRejected("interactive_step_required")
        }
        try {
            json.decodeFromString<PortalMeDto>(me.body)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:me", e)
        }
    }

    private fun selectChildAccount(): PortalAccountDto {
        val accountsRes = session.get(
            url = endpoints.portalUrl(ProtocolPaths.PORTAL_ACCOUNTS_PATH),
            headers = mapOf("Accept" to "application/json"),
        )
        if (accountsRes.code >= 500) {
            throw JournalUnavailable("http_${accountsRes.code}")
        }
        if (accountsRes.code == 401 || accountsRes.code == 403) {
            throw AuthRejected("rejected")
        }
        if (accountsRes.code !in 200..299) {
            throw JournalUnavailable("http_${accountsRes.code}")
        }
        val parsed = try {
            json.decodeFromString<PortalAccountsResponseDto>(accountsRes.body)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:accounts", e)
        }
        return ChildSelector.select(parsed.accounts, configuredChild)
    }

    private fun fetchFreshChildAccount(childLogin: String): PortalAccountDto? {
        val res = session.get(
            url = endpoints.portalUrl("${ProtocolPaths.PORTAL_FRESH_ACCOUNT_PATH}/$childLogin"),
            headers = mapOf("Accept" to "application/json"),
        )
        if (res.code == 401 || res.code == 403) {
            return null
        }
        if (res.code !in 200..299) {
            throw JournalUnavailable("http_${res.code}")
        }
        return try {
            json.decodeFromString<PortalAccountDto>(res.body)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:fresh_account", e)
        }
    }

    private fun extractCsrfToken(html: String): String {
        val doc = Jsoup.parse(html)
        val token = doc.selectFirst("input[name=_token]")?.attr("value")?.trim()?.ifEmpty { null }
        if (token != null) {
            return token
        }
        if (hasInteractiveStep(html)) {
            throw AuthRejected("interactive_step_required")
        }
        throw JournalUnavailable("layout_changed:login")
    }

    private fun hasInteractiveStep(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("g-recaptcha") ||
            lower.contains("hcaptcha") ||
            lower.contains("two-factor") ||
            lower.contains("2fa") ||
            lower.contains("verification_code")
    }
}
