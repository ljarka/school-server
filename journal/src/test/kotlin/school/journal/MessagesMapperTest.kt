package school.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class MessagesMapperTest {
    @Test
    fun `parses Europe Warsaw local datetime to UTC Instant`() {
        // 2026-11-16 14:30:00 in CET (+01:00) is 13:30:00Z
        val instant = MessagesMapper.parseSentAt("2026-11-16 14:30:00")
        assertEquals(Instant.parse("2026-11-16T13:30:00Z"), instant)
    }

    @Test
    fun `sanitises HTML by removing scripts, event handlers, and styles while preserving rel=noopener`() {
        val raw =
            """<p onclick="alert(1)" style="color:red">Cześć!</p>""" +
                """<script>evil()</script><br><a href="https://example.test">Link</a>"""
        val clean = MessagesMapper.sanitiseHtml(raw)

        assertFalse(clean.contains("<script"))
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("style="))
        assertTrue(clean.contains("Cześć!"))
        assertTrue(clean.contains("rel=\"noopener\""))
    }
}
