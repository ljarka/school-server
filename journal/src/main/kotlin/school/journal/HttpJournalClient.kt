package school.journal

import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MultipartBody
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
    private val messagesPageLimit: Int = 300,
) : JournalClient {
    private val logger = LoggerFactory.getLogger(HttpJournalClient::class.java)
    private val session = PortalSession(endpoints = endpoints, minDelayMs = minRequestDelayMs)
    private val tokenProvider = TokenProvider()
    private val json = Json { ignoreUnknownKeys = true }
    private val isGatewayMode = !login.contains("@")

    @Volatile
    private var messagesActivated = false

    override fun login() {
        if (isGatewayMode) {
            performGatewayLogin()
        } else {
            val account = performPortalLogin()
            tokenProvider.setAccount(account)
        }
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

    override fun fetchMessageHeaders(): List<MessageHeader> {
        val result = mutableListOf<MessageHeader>()
        val seenIds = HashSet<String>()

        for (page in 1..MAX_MESSAGE_PAGES) {
            val path = "${ProtocolPaths.MESSAGES_INBOX_PATH}?page=$page&limit=$messagesPageLimit"
            val raw = getMessagesJson(path)
            val parsed = try {
                json.decodeFromString<MessagesListResponseDto>(raw)
            } catch (e: Exception) {
                throw JournalUnavailable("layout_changed:messages", e)
            }

            if (parsed.data.isEmpty()) {
                break
            }

            for (item in parsed.data) {
                val header = MessagesMapper.mapHeader(item)
                if (seenIds.add(header.sourceId)) {
                    result.add(header)
                    if (result.size >= MAX_MESSAGES_TOTAL) {
                        return result
                    }
                }
            }

            if (parsed.data.size < messagesPageLimit) {
                break
            }
        }

        return result
    }

    override fun fetchMessage(sourceId: String): Message {
        val rawId = sourceId.removePrefix("m_")
        val raw = getMessagesJson("${ProtocolPaths.MESSAGES_INBOX_PATH}/$rawId")
        val parsed = try {
            json.decodeFromString<MessageDetailResponseDto>(raw)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:messages", e)
        }
        return MessagesMapper.mapDetail(parsed.data)
    }

    private fun getMessagesJson(path: String): String {
        if (isGatewayMode && !messagesActivated) {
            activateGatewayMessages()
        }

        val res = session.get(
            url = endpoints.messagesUrl(path),
            headers = mapOf("Accept" to "application/json"),
        )
        if ((res.code == 401 || res.code == 403) && isGatewayMode) {
            performGatewayLogin()
            activateGatewayMessages()
            val retry = session.get(
                url = endpoints.messagesUrl(path),
                headers = mapOf("Accept" to "application/json"),
            )
            if (retry.code == 401 || retry.code == 403) {
                throw JournalUnavailable("auth_refresh_failed")
            }
            if (retry.code >= 500) {
                throw JournalUnavailable("http_${retry.code}")
            }
            if (retry.code !in 200..299) {
                throw JournalUnavailable("http_${retry.code}")
            }
            return retry.body
        }
        if (res.code == 401 || res.code == 403) {
            throw JournalUnavailable("auth_refresh_failed")
        }
        if (res.code >= 500) {
            throw JournalUnavailable("http_${res.code}")
        }
        if (res.code !in 200..299) {
            throw JournalUnavailable("http_${res.code}")
        }
        return res.body
    }

    /**
     * Performs a GET request to JSON API v3 (bearer) or Gateway API 2.0 (cookie),
     * refreshing once on 401.
     */
    internal fun getApiJson(path: String): String {
        if (isGatewayMode) {
            return getGatewayApiJson(path)
        }

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

    private fun getGatewayApiJson(path: String): String {
        val url = endpoints.gatewayUrl("${ProtocolPaths.GATEWAY_API_PREFIX}$path")
        val headers = mapOf(
            "Accept" to "application/json",
            "Origin" to ProtocolPaths.GATEWAY_ORIGIN,
            "User-Agent" to ProtocolPaths.GATEWAY_USER_AGENT,
        )
        val first = session.get(url = url, headers = headers)
        if (first.code == 401 || first.code == 403) {
            logger.debug("e-journal gateway returned {}, re-authenticating", first.code)
            performGatewayLogin()
            val retry = session.get(url = url, headers = headers)
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

    private fun performGatewayLogin() {
        val initUrl = endpoints.gatewayUrl(
            "${ProtocolPaths.GATEWAY_PORTAL_INIT_PATH}?v=${System.currentTimeMillis() / 1000.0}",
        )
        val initRes = session.get(
            url = initUrl,
            headers = mapOf("Referer" to endpoints.portalUrl("/")),
        )
        checkGatewayStep(initRes)

        val authUrl = endpoints.apiOriginUrl(ProtocolPaths.OAUTH_AUTHORIZATION_PATH)
        checkGatewayStep(session.get(authUrl))

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
                "X-Requested-With" to "XMLHttpRequest",
                "X-Baner" to createBanerHeader(),
                "Referer" to authUrl,
            ),
        )
        if (postRes.code >= 500) {
            throw JournalUnavailable("http_${postRes.code}")
        }
        if (postRes.code == 400 || postRes.code == 401 || postRes.code == 403 || postRes.code == 422) {
            throw AuthRejected("rejected")
        }
        if (postRes.code !in 200..299) {
            throw JournalUnavailable("http_${postRes.code}")
        }
        if (postRes.body.contains("\"status\":\"error\"", ignoreCase = true) ||
            postRes.body.contains("\"errors\"", ignoreCase = true)
        ) {
            throw AuthRejected("rejected")
        }

        val performUrl = endpoints.apiOriginUrl(ProtocolPaths.OAUTH_PERFORM_LOGIN_PATH)
        checkGatewayStep(session.get(performUrl))
        checkGatewayStep(session.get(performUrl))

        val grantUrl = endpoints.apiOriginUrl(ProtocolPaths.OAUTH_GRANT_PATH)
        checkGatewayStep(session.get(grantUrl))

        val tokenInfoRes = session.get(
            url = endpoints.gatewayUrl(ProtocolPaths.GATEWAY_TOKEN_INFO_PATH),
            headers = mapOf("Accept" to "application/json"),
        )
        checkGatewayStep(tokenInfoRes)
        messagesActivated = false
    }

    private fun activateGatewayMessages() {
        val res = session.get(
            url = endpoints.gatewayUrl(ProtocolPaths.MESSAGES_ACTIVATION_PATH),
            headers = mapOf("User-Agent" to BROWSER_USER_AGENT),
        )
        if (res.code >= 500) {
            throw JournalUnavailable("http_${res.code}")
        }
        if (res.code !in 200..299) {
            throw JournalUnavailable("http_${res.code}")
        }
        messagesActivated = true
    }

    private fun checkGatewayStep(res: HttpResponseData) {
        if (res.code >= 500) {
            throw JournalUnavailable("http_${res.code}")
        }
        if (res.code == 401 || res.code == 403) {
            throw AuthRejected("rejected")
        }
        if (res.code !in 200..299) {
            throw JournalUnavailable("http_${res.code}")
        }
        if (hasInteractiveStep(res.body)) {
            throw AuthRejected("interactive_step_required")
        }
    }

    private fun createBanerHeader(): String {
        val ts = encodeBanerSegment(System.currentTimeMillis().toString())
        val rnd = encodeBanerSegment(Math.random().toString())
        return "${rnd}_$ts"
    }

    private fun encodeBanerSegment(value: String): String = value.map { (it.code + 20).toChar() }.joinToString("")

    private fun hasInteractiveStep(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("g-recaptcha") ||
            lower.contains("hcaptcha") ||
            lower.contains("two-factor") ||
            lower.contains("2fa") ||
            lower.contains("verification_code")
    }

    private companion object {
        const val MAX_MESSAGE_PAGES = 50
        const val MAX_MESSAGES_TOTAL = 5_000
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/62.0"
    }
}
