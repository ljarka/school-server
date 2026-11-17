package school.server.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.io.TempDir
import school.model.Attachment
import school.model.Grade
import school.model.GradeKind
import school.model.Message
import school.server.store.db.SchoolDatabase

class StoreTest {
    @TempDir
    lateinit var tempDir: File

    private val now = Instant.parse("2026-11-16T12:00:00Z")

    private fun openStore(relativePath: String = "school.db"): Store {
        val dbFile = File(tempDir, relativePath)
        val driver = DatabaseFactory.open(dbFile.absolutePath)
        return Store(SchoolDatabase(driver))
    }

    @Test
    fun `creates fresh database in missing directory`() {
        val dbFile = File(tempDir, "nested/dir/school.db")
        val driver = DatabaseFactory.open(dbFile.absolutePath)
        driver.close()

        assertTrue(dbFile.exists())
    }

    @Test
    fun `grade round-trip, filters, and soft delete`() {
        val store = openStore()
        val g1 = Grade(
            sourceId = "g_1",
            subject = "Matematyka",
            value = "4+",
            numericValue = 4.5,
            weight = 3,
            category = "Sprawdzian",
            semester = 1,
            kind = GradeKind.REGULAR,
            countsToAverage = true,
            teacher = "Jan Kowalski",
            comment = "Good work",
            issuedOn = LocalDate.parse("2026-11-10"),
        )
        val g2 = g1.copy(sourceId = "g_2", subject = "Polski", semester = 2, value = "5", numericValue = 5.0)

        store.transaction {
            upsertGrade(g1, contentHash = "h1", now = now)
            upsertGrade(g2, contentHash = "h2", now = now)
        }

        val fetched = store.getGrade("g_1")
        assertNotNull(fetched)
        assertEquals(g1, fetched.grade)
        assertEquals(2, store.listGrades().size)
        assertEquals(1, store.listGrades(semester = 1).size)
        assertEquals(1, store.listGrades(subject = "Polski").size)

        store.softDeleteGrade("g_2", now)
        assertEquals(1, store.listGrades().size)
        assertEquals(2, store.listGrades(includeDeleted = true).size)
    }

    @Test
    fun `message round-trip, read state, and soft delete`() {
        val store = openStore()
        val m1 = Message(
            sourceId = "m_1",
            sender = "Anna Nowak",
            subject = "Wycieczka",
            sentAt = now,
            bodyText = "Szczegoly jutro",
            bodyHtml = "<p>Szczegoly jutro</p>",
            attachments = listOf(Attachment("plan.pdf")),
        )
        val m2 = m1.copy(sourceId = "m_2", subject = "Zebranie", attachments = emptyList())

        store.transaction {
            upsertMessage(m1, contentHash = "mh1", now = now)
            upsertMessage(m2, contentHash = "mh2", now = now)
        }

        val fetched = store.getMessage("m_1")
        assertNotNull(fetched)
        assertEquals("Szczegoly jutro", fetched.bodyText)
        assertEquals(listOf(Attachment("plan.pdf")), fetched.attachments)
        assertNull(fetched.readAt)

        assertEquals(2, store.listMessages(unreadOnly = true).size)
        store.markMessageRead("m_1", now)
        assertEquals(1, store.listMessages(unreadOnly = true).size)
        store.markMessageUnread("m_1")
        assertEquals(2, store.listMessages(unreadOnly = true).size)

        store.softDeleteMessage("m_2", now)
        assertEquals(1, store.listMessages().size)
        assertEquals(2, store.listMessages(includeDeleted = true).size)
    }

    private fun message(id: String, sentAt: Instant) =
        Message(id, "Anna", "Temat $id", sentAt, "Body", "<p>Body</p>", emptyList())

    @Test
    fun `messages within the same second sort by time, not by text`() {
        val store = openStore()
        val whole = Instant.parse("2026-11-16T12:00:00Z")
        val half = Instant.parse("2026-11-16T12:00:00.500Z")
        store.upsertMessage(message("m_a", whole), contentHash = "a", now = now)
        store.upsertMessage(message("m_b", half), contentHash = "b", now = now)

        assertEquals(listOf("m_b", "m_a"), store.listMessages().map { it.sourceId })
        assertEquals(
            listOf("m_b"),
            store.listMessagesPage(false, false, since = half, after = null, limit = 10, childId = null).map {
                it.sourceId
            },
        )
    }

    @Test
    fun `message pages continue after the cursor`() {
        val store = openStore()
        (1..5).forEach { i -> store.upsertMessage(message("m_$i", now.plus(i, DateTimeUnit.MINUTE)), "h$i", now) }

        val first = store.listMessagesPage(false, false, since = null, after = null, limit = 2, childId = null)
        val last = first.last()
        val second = store.listMessagesPage(
            false,
            false,
            null,
            after = last.sentAt to last.sourceId,
            limit = 2,
            childId = null,
        )

        assertEquals(listOf("m_5", "m_4"), first.map { it.sourceId })
        assertEquals(listOf("m_3", "m_2"), second.map { it.sourceId })
    }

    @Test
    fun `mark all read returns the number of messages it changed`() {
        val store = openStore()
        store.upsertMessage(message("m_1", now), "h1", now)
        store.upsertMessage(message("m_2", now), "h2", now, childId = "102")
        store.markMessageRead("m_1", now)

        assertEquals(1L, store.markAllMessagesRead(now))
        assertEquals(0L, store.markAllMessagesRead(now))
    }

    @Test
    fun `interrupted sync runs are closed as errors`() {
        val store = openStore()
        store.insertSyncRun(now)

        store.failInterruptedSyncRuns(now)

        val run = assertNotNull(store.getLastSyncRun())
        assertEquals(school.model.SyncRunStatus.ERROR, run.status)
        assertEquals("interrupted", run.errorCode)
    }

    @Test
    fun `child selector matches account id, login, or a child id that has data`() {
        val store = openStore()
        store.upsertChildAccounts(listOf(school.model.ChildAccount("101", "123u", "Ola")), now)
        store.upsertMessage(message("m_1", now), "h1", now)

        assertEquals("101", store.findChildId("101"))
        assertEquals("101", store.findChildId("123u"))
        assertEquals("default", store.findChildId("default"))
        assertNull(store.findChildId("999"))
    }
}
