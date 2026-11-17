package school.journal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import school.model.AuthRejected
import school.model.JournalUnavailable

class HttpJournalClientLoginTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/auth/$name")?.readText()) {
            "Missing fixture: $name"
        }

    private fun endpoints(): JournalEndpoints {
        val base = server.url("/").toString().trimEnd('/')
        return JournalEndpoints(portal = base, api = base, messages = base)
    }

    private fun client(configuredChild: String? = null): HttpJournalClient = HttpJournalClient(
        endpoints = endpoints(),
        login = "parent@example.test",
        passwordProvider = { "secret-journal-password" },
        configuredChild = configuredChild,
        minRequestDelayMs = 0L,
    )

    private fun enqueueHappyLogin() {
        server.enqueue(MockResponse(code = 200, body = fixture("login_page.html")))
        server.enqueue(MockResponse(code = 200, body = "ok"))
        server.enqueue(MockResponse(code = 200, body = fixture("me.json")))
        server.enqueue(MockResponse(code = 200, body = fixture("accounts.json")))
    }

    @Test
    fun `happy path logs in, selects child, and uses bearer token on API call`() {
        enqueueHappyLogin()
        server.enqueue(MockResponse(code = 200, body = """{"Grades":[]}"""))

        val journal = client()
        journal.login()
        val apiBody = journal.getApiJson(ProtocolPaths.API_GRADES_PATH)

        assertEquals("""{"Grades":[]}""", apiBody)
        val loginPost = server.takeRequest() // GET login page
        assertEquals("GET", loginPost.method)
        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertTrue(postReq.body?.utf8()?.contains("_token=csrf-token-123") == true)
        server.takeRequest() // Me
        server.takeRequest() // Accounts
        val apiReq = server.takeRequest()
        assertEquals("Bearer fake-child-token-101", apiReq.headers["Authorization"])
    }

    @Test
    fun `wrong password throws AuthRejected after exactly one login POST`() {
        server.enqueue(MockResponse(code = 200, body = fixture("login_page.html")))
        server.enqueue(MockResponse(code = 401, body = "Unauthorized"))

        assertFailsWith<AuthRejected> {
            client().login()
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `503 at any login step throws JournalUnavailable, never AuthRejected`() {
        server.enqueue(MockResponse(code = 200, body = fixture("login_page.html")))
        server.enqueue(MockResponse(code = 503, body = "Service Unavailable"))

        assertFailsWith<JournalUnavailable> {
            client().login()
        }
    }

    @Test
    fun `selects child by id, by login, or throws child_not_found`() {
        enqueueHappyLogin()
        server.enqueue(MockResponse(code = 200, body = "{}"))
        val byId = client(configuredChild = "102")
        byId.login()
        byId.getApiJson(ProtocolPaths.API_GRADES_PATH)
        repeat(4) { server.takeRequest() }
        assertEquals("Bearer fake-child-token-102", server.takeRequest().headers["Authorization"])

        enqueueHappyLogin()
        server.enqueue(MockResponse(code = 200, body = "{}"))
        val byLogin = client(configuredChild = "654321u")
        byLogin.login()
        byLogin.getApiJson(ProtocolPaths.API_GRADES_PATH)
        repeat(4) { server.takeRequest() }
        assertEquals("Bearer fake-child-token-102", server.takeRequest().headers["Authorization"])

        enqueueHappyLogin()
        val unknown = assertFailsWith<AuthRejected> {
            client(configuredChild = "999").login()
        }
        assertEquals("child_not_found", unknown.message)
    }

    @Test
    fun `expired token refreshes once and retries, second 401 throws JournalUnavailable`() {
        enqueueHappyLogin()
        server.enqueue(MockResponse(code = 401, body = "Expired"))
        server.enqueue(MockResponse(code = 200, body = fixture("fresh_account.json")))
        server.enqueue(MockResponse(code = 200, body = """{"ok":true}"""))

        val journal = client()
        journal.login()
        assertEquals("""{"ok":true}""", journal.getApiJson(ProtocolPaths.API_GRADES_PATH))

        repeat(6) { server.takeRequest() }
        val retriedReq = server.takeRequest()
        assertEquals("Bearer refreshed-child-token-101", retriedReq.headers["Authorization"])

        // Second 401 after refresh -> JournalUnavailable
        server.enqueue(MockResponse(code = 401, body = "Expired"))
        server.enqueue(MockResponse(code = 200, body = fixture("fresh_account.json")))
        server.enqueue(MockResponse(code = 401, body = "Still expired"))

        assertFailsWith<JournalUnavailable> {
            journal.getApiJson(ProtocolPaths.API_GRADES_PATH)
        }
    }

    @Test
    fun `redirect to foreign host throws JournalUnavailable unexpected_redirect`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://evil.example.test/phish")
                .build(),
        )

        val ex = assertFailsWith<JournalUnavailable> {
            client().login()
        }
        assertEquals("unexpected_redirect", ex.message)
    }

    @Test
    fun `gateway numeric login authenticates via OAuth and fetches grades via gateway`() {
        val base = server.url("/").toString().trimEnd('/')
        val gatewayClient = HttpJournalClient(
            endpoints = JournalEndpoints(portal = base, api = "$base/3.0", messages = base, gateway = base),
            login = "1234567u",
            passwordProvider = { "secret-journal-password" },
            minRequestDelayMs = 0L,
        )

        server.enqueue(MockResponse(code = 200, body = "ok")) // portal init
        server.enqueue(MockResponse(code = 200, body = "auth")) // GET OAuth/Authorization
        server.enqueue(MockResponse(code = 200, body = """{"status":"ok"}""")) // POST credentials
        server.enqueue(MockResponse(code = 200, body = "ok")) // 2FA -> Grant
        server.enqueue(MockResponse(code = 200, body = """{"UserIdentifier":"1234567u"}""")) // TokenInfo
        server.enqueue(MockResponse(code = 200, body = "{}")) // UserInfo
        server.enqueue(MockResponse(code = 200, body = """{"Grades":[]}""")) // GET /gateway/api/2.0/Grades

        gatewayClient.login()
        assertEquals(emptyList(), gatewayClient.fetchGrades())
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `gateway numeric login rejects wrong credentials with AuthRejected`() {
        val base = server.url("/").toString().trimEnd('/')
        val gatewayClient = HttpJournalClient(
            endpoints = JournalEndpoints(portal = base, api = "$base/3.0", messages = base, gateway = base),
            login = "1234567u",
            passwordProvider = { "wrong" },
            minRequestDelayMs = 0L,
        )

        server.enqueue(MockResponse(code = 200, body = "ok"))
        server.enqueue(MockResponse(code = 200, body = "auth"))
        server.enqueue(MockResponse(code = 400, body = """{"status":"error"}"""))

        assertFailsWith<AuthRejected> { gatewayClient.login() }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `debug logs never leak password or bearer token`() {
        val journalLogger = LoggerFactory.getLogger("school.journal") as Logger
        val previousLevel = journalLogger.level
        journalLogger.level = Level.DEBUG
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        journalLogger.addAppender(appender)

        try {
            enqueueHappyLogin()
            server.enqueue(MockResponse(code = 200, body = "{}"))

            val journal = client()
            journal.login()
            journal.getApiJson(ProtocolPaths.API_GRADES_PATH)

            val allLogs = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(allLogs.contains("e-journal"))
            assertFalse(allLogs.contains("secret-journal-password"))
            assertFalse(allLogs.contains("fake-child-token-101"))
        } finally {
            journalLogger.detachAppender(appender)
            journalLogger.level = previousLevel
        }
    }

    @Test
    fun `listChildren returns all linked child accounts when SCHOOL_CHILD is unset`() {
        enqueueHappyLogin()
        val journal = client()
        journal.login()
        val children = journal.listChildren()
        assertEquals(listOf("101", "102"), children.map { it.id })
        assertEquals(listOf("123456u", "654321u"), children.map { it.login })
    }
}
