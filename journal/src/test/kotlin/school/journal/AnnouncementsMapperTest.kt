package school.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import school.model.JournalUnavailable
import school.model.MessageType

class AnnouncementsMapperTest {
    private val users = mapOf(20L to UserDto(20, "Jan", "Kowalski"))

    @Test
    fun `missing creation date falls back to start of the start day in Warsaw`() {
        val msg = AnnouncementsMapper.mapOne(
            SchoolNoticeDto(id = JsonPrimitive(5), subject = "Wywiadówka", startDate = "2026-11-20"),
            users,
        )
        // Midnight in CET (+01:00) is 23:00Z the day before.
        assertEquals(Instant.parse("2026-11-19T23:00:00Z"), msg.sentAt)
        assertEquals("a_5", msg.sourceId)
        assertEquals(MessageType.ANNOUNCEMENT, msg.type)
        assertEquals("", msg.sender)
    }

    @Test
    fun `missing id or any date is a layout change`() {
        val noId = assertFailsWith<JournalUnavailable> {
            AnnouncementsMapper.mapOne(SchoolNoticeDto(subject = "x", startDate = "2026-11-20"), users)
        }
        assertEquals("layout_changed:announcements", noId.message)

        assertFailsWith<JournalUnavailable> {
            AnnouncementsMapper.mapOne(SchoolNoticeDto(id = JsonPrimitive("1"), subject = "x"), users)
        }
        assertFailsWith<JournalUnavailable> {
            AnnouncementsMapper.mapOne(SchoolNoticeDto(id = JsonPrimitive("1"), startDate = "20.11.2026"), users)
        }
    }

    @Test
    fun `plain text content keeps line breaks and is escaped`() {
        assertEquals("2 &lt; 3 &amp; 4<br>c", AnnouncementsMapper.toHtml("2 < 3 & 4\nc"))
        val msg = AnnouncementsMapper.mapOne(
            SchoolNoticeDto(
                id = JsonPrimitive("x"),
                startDate = "2026-11-20",
                endDate = "2026-11-21T00:00:00",
                content = "Linia 1\r\nLinia 2",
            ),
            users,
        )
        assertEquals("Linia 1\nLinia 2", msg.bodyText)
        assertEquals(LocalDate.parse("2026-11-21"), msg.validTo)
        assertNull(msg.attachments.firstOrNull())
    }
}
