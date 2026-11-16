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

    private fun client(pageLimit: Int = 2): HttpJournalClient {
        val base = server.url("/").toString().trimEnd('/')
        return HttpJournalClient(
            endpoints = JournalEndpoints(portal = base, api = base, messages = base),
            login = "parent@example.test",
            passwordProvider = { "secret" },
            minRequestDelayMs = 0L,
            messagesPageLimit = pageLimit,
        )
    }

    @Test
    fun `paging collects all pages using GET only`() {
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_page1.json")))
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_page2.json")))

        val headers = client(pageLimit = 2).fetchMessageHeaders()

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

        repeat(2) {
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test
    fun `empty inbox returns empty list`() {
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_empty.json")))

        assertEquals(emptyList(), client().fetchMessageHeaders())
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `broken inbox json throws JournalUnavailable layout_changed_messages`() {
        server.enqueue(MockResponse(code = 200, body = fixture("inbox_broken.json")))

        val ex = assertFailsWith<JournalUnavailable> { client().fetchMessageHeaders() }
        assertEquals("layout_changed:messages", ex.message)
    }

    @Test
    fun `fetchMessage parses attachments and sanitises html body using GET only`() {
        server.enqueue(MockResponse(code = 200, body = fixture("message_with_attachments.json")))
        val withAttachments = client().fetchMessage("m_4471")
        assertEquals(
            listOf(Attachment("plan_wycieczki.pdf"), Attachment("zgoda_rodzicow.docx")),
            withAttachments.attachments,
        )
        assertEquals("Plan wycieczki w załączniku.", withAttachments.bodyText)
        assertEquals("GET", server.takeRequest().method)

        server.enqueue(MockResponse(code = 200, body = fixture("message_html_body.json")))
        val htmlMsg = client().fetchMessage("m_4472")
        assertFalse(htmlMsg.bodyHtml.contains("<script"))
        assertFalse(htmlMsg.bodyHtml.contains("onclick"))
        assertFalse(htmlMsg.bodyHtml.contains("style="))
        assertTrue(htmlMsg.bodyText.contains("Dzień dobry!"))
        assertEquals("GET", server.takeRequest().method)
    }
}
