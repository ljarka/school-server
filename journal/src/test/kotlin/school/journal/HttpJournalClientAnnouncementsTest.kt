package school.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import school.model.JournalUnavailable
import school.model.MessageType

class HttpJournalClientAnnouncementsTest {
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

    private fun fixture(path: String): String = checkNotNull(javaClass.getResource("/fixtures/$path")?.readText())

    private fun loggedInClient(): HttpJournalClient {
        server.enqueue(MockResponse(code = 200, body = fixture("auth/login_page.html")))
        server.enqueue(MockResponse(code = 200, body = "ok"))
        server.enqueue(MockResponse(code = 200, body = fixture("auth/me.json")))
        server.enqueue(MockResponse(code = 200, body = fixture("auth/accounts.json")))

        val base = server.url("/").toString().trimEnd('/')
        return HttpJournalClient(
            endpoints = JournalEndpoints(portal = base, api = base, messages = base),
            login = "parent@example.test",
            passwordProvider = { "secret" },
            minRequestDelayMs = 0L,
        ).apply { login() }
    }

    private fun drainRequests(): List<String> = buildList {
        while (true) {
            val request = server.takeRequest(timeout = 0, unit = java.util.concurrent.TimeUnit.SECONDS) ?: break
            add("${request.method} ${request.url.encodedPath}")
        }
    }

    @Test
    fun `fetchAnnouncements maps notices with author, dates, and Polish characters using only GET`() {
        val journal = loggedInClient()
        drainRequests()
        server.enqueue(MockResponse(code = 200, body = fixture("announcements/notices.json")))
        server.enqueue(MockResponse(code = 200, body = fixture("grades/users.json")))

        val items = journal.fetchAnnouncements()!!

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("a_1f3a9c", first.sourceId)
        assertEquals(MessageType.ANNOUNCEMENT, first.type)
        assertEquals("Jan Kowalski", first.sender)
        assertEquals("Zbiórka makulatury", first.subject)
        // 18:45 in CET (+01:00) is 17:45Z.
        assertEquals(Instant.parse("2026-11-15T17:45:00Z"), first.sentAt)
        assertEquals(LocalDate.parse("2026-11-16"), first.validFrom)
        assertEquals(LocalDate.parse("2026-11-27"), first.validTo)
        assertEquals(
            "Drodzy Rodzice,\nw piątek zbieramy makulaturę.\nDziękujemy za pomoc & wsparcie!",
            first.bodyText,
        )
        assertTrue(first.attachments.isEmpty())

        val second = items[1]
        assertEquals("a_77", second.sourceId)
        assertEquals("", second.sender, "unknown author")
        assertNull(second.validTo)
        assertEquals(Instant.parse("2026-11-30T23:00:00Z"), second.sentAt, "no creation date: start of start day")

        val requests = drainRequests()
        assertEquals(listOf("GET /SchoolNotices", "GET /Users"), requests)
    }

    @Test
    fun `html content is sanitised`() {
        val journal = loggedInClient()
        server.enqueue(MockResponse(code = 200, body = fixture("announcements/notices_html.json")))

        val item = journal.fetchAnnouncements()!!.single()

        assertFalse(item.bodyHtml.contains("<script"))
        assertFalse(item.bodyHtml.contains("onclick"))
        assertFalse(item.bodyHtml.contains("style="))
        assertEquals("Ubieramy się na żółto.", item.bodyText)
        assertEquals("Dzień Życzliwości", item.subject)
    }

    @Test
    fun `empty board returns empty list without user lookup`() {
        val journal = loggedInClient()
        server.enqueue(MockResponse(code = 200, body = fixture("announcements/notices_empty.json")))

        assertEquals(emptyList(), journal.fetchAnnouncements())
    }

    @Test
    fun `broken json throws layout_changed_announcements`() {
        val journal = loggedInClient()
        server.enqueue(MockResponse(code = 200, body = fixture("announcements/notices_broken.json")))

        val ex = assertFailsWith<JournalUnavailable> { journal.fetchAnnouncements() }
        assertEquals("layout_changed:announcements", ex.message)
    }

    @Test
    fun `AccessDeny returns null so stored announcements are kept`() {
        val journal = loggedInClient()
        server.enqueue(
            MockResponse(
                code = 403,
                body = """{"Status":"Error","Code":"AccessDeny","Message":"No access to this resource"}""",
            ),
        )

        assertNull(journal.fetchAnnouncements())
    }
}
