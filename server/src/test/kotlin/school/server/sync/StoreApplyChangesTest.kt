package school.server.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.io.TempDir
import school.model.ChangeType
import school.model.Grade
import school.model.GradeKind
import school.model.SyncRunStatus
import school.server.store.DatabaseFactory
import school.server.store.Store
import school.server.store.db.SchoolDatabase

class StoreApplyChangesTest {
    @TempDir
    lateinit var tempDir: File

    private val detector = ChangeDetector()
    private val t1 = Instant.parse("2026-11-16T10:00:00Z")
    private val t2 = Instant.parse("2026-11-16T10:15:00Z")

    private fun openStore(): Store {
        val driver = DatabaseFactory.open(File(tempDir, "school.db").absolutePath)
        return Store(SchoolDatabase(driver))
    }

    private fun grade(id: String, value: String) = Grade(
        sourceId = id,
        subject = "Matematyka",
        value = value,
        numericValue = value.toDoubleOrNull(),
        weight = 2,
        category = "Sprawdzian",
        semester = 1,
        kind = GradeKind.REGULAR,
        countsToAverage = true,
        teacher = "Jan Kowalski",
        comment = null,
        issuedOn = LocalDate.parse("2026-11-10"),
    )

    @Test
    fun `event ids strictly increase across two runs`() {
        val store = openStore()

        val run1 = store.insertSyncRun(t1)
        val g1 = grade("g_1", "4")
        val c1 = detector.diffGrades(store.listGrades(includeDeleted = true), listOf(g1), complete = true)
        store.applyChanges(run1, c1, Changes(), t1)
        store.finishSyncRun(run1, t1, SyncRunStatus.OK, null, 1, c1.totalCount)

        val run2 = store.insertSyncRun(t2)
        val g1Updated = grade("g_1", "5")
        val c2 = detector.diffGrades(store.listGrades(includeDeleted = true), listOf(g1Updated), complete = true)
        store.applyChanges(run2, c2, Changes(), t2)
        store.finishSyncRun(run2, t2, SyncRunStatus.OK, null, 1, c2.totalCount)

        val events = store.listChangeEventsAfter(0L)
        assertEquals(2, events.size)
        assertEquals(ChangeType.CREATED, events[0].changeType)
        assertEquals(ChangeType.UPDATED, events[1].changeType)
        assertTrue(events[1].id > events[0].id)
    }

    @Test
    fun `transaction rolls back rows and events on exception`() {
        val store = openStore()
        val runId = store.insertSyncRun(t1)

        assertFailsWith<IllegalStateException> {
            store.transaction {
                upsertGrade(grade("g_1", "5"), contentHash = "h1", now = t1)
                throw IllegalStateException("Simulated failure")
            }
        }

        assertTrue(store.listGrades(includeDeleted = true).isEmpty())
        assertTrue(store.listChangeEventsAfter(0L).isEmpty())
        store.finishSyncRun(runId, t1, SyncRunStatus.ERROR, "failed", 0, 0)
    }
}
