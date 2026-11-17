package school.server.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import school.model.Attachment
import school.model.Grade
import school.model.GradeKind
import school.model.Message
import school.server.store.StoredGrade
import school.server.store.StoredMessage

class ChangeDetectorTest {
    private val detector = ChangeDetector()
    private val t0 = Instant.parse("2026-11-16T10:00:00Z")

    private fun grade(id: String = "g_1", value: String = "4", issuedOn: String = "2026-11-10") = Grade(
        sourceId = id,
        subject = "Matematyka",
        value = value,
        numericValue = value.toDoubleOrNull(),
        weight = 2,
        category = "Kartkówka",
        semester = 1,
        kind = GradeKind.REGULAR,
        countsToAverage = true,
        teacher = "Jan Kowalski",
        comment = null,
        issuedOn = LocalDate.parse(issuedOn),
    )

    private fun message(
        id: String = "m_1",
        subject: String = "Temat",
        bodyHtml: String = "<p>Treść</p>",
        sentAt: String = "2026-11-16T09:00:00Z",
    ) = Message(
        sourceId = id,
        sender = "Anna Nowak",
        subject = subject,
        sentAt = Instant.parse(sentAt),
        bodyText = "Treść",
        bodyHtml = bodyHtml,
        attachments = listOf(Attachment("a.pdf")),
    )

    @Test
    fun `empty stored marks all fresh items created in stable order`() {
        val g2 = grade(id = "g_2", issuedOn = "2026-11-12")
        val g1 = grade(id = "g_1", issuedOn = "2026-11-10")

        val changes = detector.diffGrades(stored = emptyList(), fresh = listOf(g2, g1), complete = true)

        assertEquals(listOf("g_1", "g_2"), changes.created.map { it.item.sourceId })
        assertTrue(changes.updated.isEmpty())
        assertTrue(changes.deletedIds.isEmpty())
    }

    @Test
    fun `one field changed produces Updated with only that field in diff`() {
        val oldGrade = grade(id = "g_1", value = "4")
        val newGrade = grade(id = "g_1", value = "5")
        val stored = StoredGrade(
            grade = oldGrade,
            contentHash = ContentHash.gradeHash(oldGrade),
            firstSeenAt = t0,
            updatedAt = t0,
            deletedAt = null,
        )

        val changes = detector.diffGrades(stored = listOf(stored), fresh = listOf(newGrade), complete = true)

        assertTrue(changes.created.isEmpty())
        assertEquals(1, changes.updated.size)
        assertEquals("""{"numericValue":[4.0,5.0],"value":["4","5"]}""", changes.updated[0].diffJson)
    }

    @Test
    fun `read state or bodyHtml change only produces no message event`() {
        val m = message()
        val stored = StoredMessage(
            sourceId = m.sourceId,
            sender = m.sender,
            subject = m.subject,
            bodyText = m.bodyText,
            bodyHtml = "<p>Old html</p>",
            sentAt = m.sentAt,
            attachments = m.attachments,
            contentHash = ContentHash.messageHash(m),
            readAt = t0,
            firstSeenAt = t0,
            updatedAt = t0,
            deletedAt = null,
        )

        val changes = detector.diffMessages(stored = listOf(stored), fresh = listOf(m), complete = true)
        assertEquals(0, changes.totalCount)
    }

    @Test
    fun `complete false never produces deleted events`() {
        val g = grade(id = "g_1")
        val stored = StoredGrade(
            grade = g,
            contentHash = ContentHash.gradeHash(g),
            firstSeenAt = t0,
            updatedAt = t0,
            deletedAt = null,
        )

        val incomplete = detector.diffGrades(stored = listOf(stored), fresh = emptyList(), complete = false)
        assertTrue(incomplete.deletedIds.isEmpty())

        val complete = detector.diffGrades(stored = listOf(stored), fresh = emptyList(), complete = true)
        assertEquals(listOf("g_1"), complete.deletedIds)
    }

    @Test
    fun `deleted item reappearing is reported as Created again`() {
        val g = grade(id = "g_1")
        val stored = StoredGrade(
            grade = g,
            contentHash = ContentHash.gradeHash(g),
            firstSeenAt = t0,
            updatedAt = t0,
            deletedAt = t0,
        )

        val changes = detector.diffGrades(stored = listOf(stored), fresh = listOf(g), complete = true)
        assertEquals(1, changes.created.size)
        assertEquals("g_1", changes.created[0].item.sourceId)
    }
}
