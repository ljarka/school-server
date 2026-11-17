package school.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import school.model.Attachment
import school.model.JournalUnavailable
import school.model.MessageHeaderListing

class HttpJournalClientMessagesTest {
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
        checkNotNull(javaClass.getResource("/fixtures/messages/$name")?.readText())

    private fun authFixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/auth/$name")?.readText())

    private fun loggedInClient(pageLimit: Int = 2, maxPages: Int = 50): HttpJournalClient {
        server.enqueue(MockResponse(code = 200, body = authFixture("login_page.html")))
        server.enqueue(MockResponse(code = 200, body = "ok"))
        server.enqueue(MockResponse(code = 200, body = authFixture("me.json")))
        server.enqueue(MockResponse(code = 200, body = authFixture("accounts.json")))

        val base = server.url("/").toString().trimEnd('/')
        val journal = HttpJournalClient(
            endpoints = JournalEndpoints(portal = base, api = base, messages = base, gateway = base),
            login = "parent@example.test",
            passwordProvider = { "secret" },
            minRequestDelayMs = 0L,
            messagesPageLimit = pageLimit,
            maxMessagePages = maxPages,
        ).apply { login() }

        repeat(4) { server.takeRequest() }
        return journal
    }

    private fun enqueueMessagesActivation() {
        server.enqueue(MockResponse(code = 200, body = """{"Token":"auto-tok-123"}"""))
        server.enqueue(MockResponse(code = 200, body = "ok"))
        server.enqueue(MockResponse(code = 200, body = "ok"))
    }

    private fun assertActivationRequests() {
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `paging collects all pages using GET only`() {
        val journal = loggedInClient(pageLimit = 2)
        enqueueMessagesActivation()
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_page1.json")))
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_page2.json")))

        val listing = journal.fetchMessageHeaders()
        assertTrue(listing.complete)
        val headers = listing.headers

        assertEquals(3, headers.size)
        assertEquals("m_4471", headers[0].sourceId)
        assertEquals("Anna Nowak", headers[0].sender)
        assertEquals("Wycieczka szkolna", headers[0].subject)
        assertEquals(Instant.parse("2026-11-16T13:30:00Z"), headers[0].sentAt)
        assertTrue(headers[0].hasAttachments)

        assertEquals("m_4470", headers[1].sourceId)
        assertEquals("Jan Kowalski", headers[1].sender)
        assertFalse(headers[1].hasAttachments)

        assertEquals("m_4469", headers[2].sourceId)
        assertEquals("Dzień wolny", headers[2].subject)

        assertActivationRequests()
        repeat(2) {
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test
    fun `empty inbox returns empty list`() {
        val journal = loggedInClient()
        enqueueMessagesActivation()
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_empty.json")))

        assertEquals(MessageHeaderListing(emptyList(), complete = true), journal.fetchMessageHeaders())
        assertActivationRequests()
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `broken inbox json throws JournalUnavailable layout_changed_messages`() {
        val journal = loggedInClient()
        enqueueMessagesActivation()
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_broken.json")))

        val ex = assertFailsWith<JournalUnavailable> { journal.fetchMessageHeaders() }
        assertEquals("layout_changed:messages", ex.message)
    }

    @Test
    fun `fetchMessage parses attachments and sanitises html body using GET only`() {
        val firstJournal = loggedInClient()
        enqueueMessagesActivation()
        server.enqueue(MockResponse(code = 200, body = fixture("message_with_attachments.json")))
        val withAttachments = firstJournal.fetchMessage("m_4471")
        assertEquals(
            listOf(Attachment("plan_wycieczki.pdf"), Attachment("zgoda_rodzicow.docx")),
            withAttachments.attachments,
        )
        assertEquals("Plan wycieczki w załączniku.", withAttachments.bodyText)
        assertActivationRequests()
        assertEquals("GET", server.takeRequest().method)

        val secondJournal = loggedInClient()
        enqueueMessagesActivation()
        server.enqueue(MockResponse(code = 200, body = fixture("message_html_body.json")))
        val htmlMsg = secondJournal.fetchMessage("m_4472")
        assertFalse(htmlMsg.bodyHtml.contains("<script"))
        assertFalse(htmlMsg.bodyHtml.contains("onclick"))
        assertFalse(htmlMsg.bodyHtml.contains("style="))
        assertTrue(htmlMsg.bodyText.contains("Dzień dobry!"))
        assertActivationRequests()
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `listing cut at the page limit is reported as incomplete`() {
        val journal = loggedInClient(pageLimit = 2, maxPages = 1)
        enqueueMessagesActivation()
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_page1.json")))

        val listing = journal.fetchMessageHeaders()

        assertEquals(2, listing.headers.size)
        assertFalse(listing.complete)
    }
}
