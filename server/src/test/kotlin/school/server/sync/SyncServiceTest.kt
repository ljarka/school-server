package school.server.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.io.TempDir
import school.model.AuthRejected
import school.model.ChangeType
import school.model.Grade
import school.model.GradeKind
import school.model.JournalClient
import school.model.JournalUnavailable
import school.model.Message
import school.model.MessageHeader
import school.model.MessageHeaderListing
import school.model.SyncRunStatus
import school.server.auth.FakeClock
import school.server.store.DatabaseFactory
import school.server.store.Store
import school.server.store.db.SchoolDatabase

@OptIn(ExperimentalCoroutinesApi::class)
class SyncServiceTest {
    @TempDir
    lateinit var tempDir: File

    private val t0 = Instant.parse("2026-11-16T12:00:00Z")

    private fun openStore(): Store {
        val driver = DatabaseFactory.open(File(tempDir, "school.db").absolutePath)
        return Store(SchoolDatabase(driver))
    }

    private class FakeJournalClient : JournalClient {
        var loginCount = 0
        var grades: List<Grade>? = emptyList()
        var headers: List<MessageHeader> = emptyList()
        var headersComplete = true
        var messagesById: Map<String, Message> = emptyMap()
        val fetchedMessageIds = mutableListOf<String>()
        var loginError: Throwable? = null
        var gradesError: Throwable? = null
        var headersError: Throwable? = null
        var onFetchGrades: (() -> Unit)? = null

        override fun login() {
            loginCount++
            loginError?.let { throw it }
        }

        override fun fetchGrades(childId: String?): List<Grade>? {
            onFetchGrades?.invoke()
            gradesError?.let { throw it }
            return grades
        }

        override fun fetchMessageHeaders(childId: String?): MessageHeaderListing {
            headersError?.let { throw it }
            return MessageHeaderListing(headers, headersComplete)
        }

        override fun fetchMessage(sourceId: String, childId: String?): Message {
            fetchedMessageIds += sourceId
            return messagesById[sourceId] ?: error("Missing fake message $sourceId")
        }
    }

    @Test
    fun `two concurrent syncNow calls run one and skip the other`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val clock = FakeClock(t0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val journal = FakeJournalClient().apply {
            onFetchGrades = {
                entered.complete(Unit)
                while (!release.isCompleted) {
                    Thread.sleep(5)
                }
            }
        }
        val service = SyncService(
            journal = journal,
            store = store,
            clock = clock,
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
        )

        val first = async(kotlinx.coroutines.Dispatchers.Default) { service.syncNow() }
        entered.await()
        val second = service.syncNow()
        release.complete(Unit)

        assertTrue(first.await() is SyncResult.Ok)
        assertEquals(SyncResult.Skipped("in_progress"), second)
    }

    @Test
    fun `AuthRejected stops all future syncs without network calls`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val clock = FakeClock(t0)
        val journal = FakeJournalClient().apply {
            loginError = AuthRejected("rejected")
        }
        val service = SyncService(journal = journal, store = store, clock = clock, ioDispatcher = dispatcher)

        val first = service.syncNow()
        val second = service.syncNow()

        assertEquals(SyncResult.AuthFailed, first)
        assertEquals(SyncResult.AuthFailed, second)
        assertEquals(1, journal.loginCount)
        assertEquals(AuthState.AUTH_FAILED, service.state().authState)
        assertEquals("auth_failed", store.getMeta("auth_state"))
    }

    @Test
    fun `unavailable 3x backs off 1, 2, 4 minutes and resets on success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val clock = FakeClock(t0)
        val journal = FakeJournalClient().apply {
            loginError = JournalUnavailable("network_error")
        }
        val service = SyncService(journal = journal, store = store, clock = clock, ioDispatcher = dispatcher)

        assertTrue(service.syncNow() is SyncResult.Failed)
        assertEquals(1.minutes, service.nextDelayDuration())

        assertTrue(service.syncNow() is SyncResult.Failed)
        assertEquals(2.minutes, service.nextDelayDuration())

        assertTrue(service.syncNow() is SyncResult.Failed)
        assertEquals(4.minutes, service.nextDelayDuration())

        journal.loginError = null
        assertTrue(service.syncNow() is SyncResult.Ok)
        assertEquals(0, service.state().consecutiveErrors)
        assertTrue(service.nextDelayDuration() >= 15.minutes)
    }

    @Test
    fun `only new messages trigger fetchMessage`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val clock = FakeClock(t0)
        val m1 = Message("m_1", "Anna", "Temat 1", t0, "Body 1", "<p>Body 1</p>", emptyList())
        val h1 = MessageHeader("m_1", "Anna", "Temat 1", t0, false)
        val journal = FakeJournalClient().apply {
            headers = listOf(h1)
            messagesById = mapOf("m_1" to m1)
        }
        val service = SyncService(journal = journal, store = store, clock = clock, ioDispatcher = dispatcher)

        assertEquals(SyncResult.Ok(1), service.syncNow())
        assertEquals(listOf("m_1"), journal.fetchedMessageIds)

        journal.fetchedMessageIds.clear()
        assertEquals(SyncResult.Ok(0), service.syncNow())
        assertTrue(journal.fetchedMessageIds.isEmpty())
    }

    @Test
    fun `partial failure leaves existing messages untouched and marks run error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val clock = FakeClock(t0)
        val m1 = Message("m_1", "Anna", "Temat 1", t0, "Body 1", "<p>Body 1</p>", emptyList())
        val h1 = MessageHeader("m_1", "Anna", "Temat 1", t0, false)
        val g1 = Grade(
            "g_1",
            "Matematyka",
            "5",
            5.0,
            2,
            "Sprawdzian",
            1,
            GradeKind.REGULAR,
            true,
            "Jan",
            null,
            LocalDate.parse("2026-11-10"),
        )

        val journal = FakeJournalClient().apply {
            grades = listOf(g1)
            headers = listOf(h1)
            messagesById = mapOf("m_1" to m1)
        }
        val service = SyncService(journal = journal, store = store, clock = clock, ioDispatcher = dispatcher)
        assertEquals(SyncResult.Ok(2), service.syncNow())

        // Now messages fail while grades succeed
        journal.headersError = JournalUnavailable("http_503")
        val result = service.syncNow()

        assertEquals(SyncResult.Failed("http_503"), result)
        assertEquals(1, store.listMessages().size)
        assertTrue(store.listChangeEventsAfter(0L).none { it.changeType == ChangeType.DELETED })
        assertEquals(SyncRunStatus.ERROR, store.getLastSyncRun()?.status)
    }

    private fun grade(id: String) = Grade(
        id,
        "Matematyka",
        "5",
        5.0,
        2,
        "Sprawdzian",
        1,
        GradeKind.REGULAR,
        true,
        "Jan",
        null,
        LocalDate.parse("2026-11-10"),
    )

    @Test
    fun `manual sync throttles within 5 minutes and succeeds after 5 minutes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val clock = FakeClock(t0)
        val journal = FakeJournalClient()
        val service = SyncService(journal = journal, store = store, clock = clock, ioDispatcher = dispatcher)

        assertNull(service.triggerManualSync(this))
        advanceUntilIdle()
        assertEquals(1, journal.loginCount)

        val tooSoon = service.triggerManualSync(this)
        assertTrue(tooSoon is SyncResult.Skipped && tooSoon.reason == "too_soon")

        clock.advance(5.minutes)
        assertNull(service.triggerManualSync(this))
        advanceUntilIdle()
        assertEquals(SyncRunStatus.OK, store.getLastSyncRun()?.status)
    }

    @Test
    fun `manual sync while another sync runs is in_progress and keeps the cooldown free`() = runTest {
        val store = openStore()
        val clock = FakeClock(t0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val journal = FakeJournalClient().apply {
            onFetchGrades = {
                entered.complete(Unit)
                while (!release.isCompleted) Thread.sleep(5)
            }
        }
        val service = SyncService(journal = journal, store = store, clock = clock, ioDispatcher = Dispatchers.IO)

        val scheduled = async(Dispatchers.Default) { service.syncNow() }
        entered.await()
        val manual = service.triggerManualSync(this)
        release.complete(Unit)
        scheduled.await()

        assertEquals(SyncResult.Skipped("in_progress"), manual)
        journal.onFetchGrades = null
        assertNull(service.triggerManualSync(this))
        advanceUntilIdle()
    }

    @Test
    fun `grades resource denied keeps stored grades and counts as success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val journal = FakeJournalClient().apply { grades = listOf(grade("g_1")) }
        val service = SyncService(journal = journal, store = store, clock = FakeClock(t0), ioDispatcher = dispatcher)
        assertEquals(SyncResult.Ok(1), service.syncNow())

        journal.grades = null
        assertEquals(SyncResult.Ok(0), service.syncNow())

        assertEquals(listOf("g_1"), store.listGrades().map { it.grade.sourceId })
        assertTrue(store.listChangeEventsAfter(0L).none { it.changeType == ChangeType.DELETED })
        assertEquals(0, service.state().consecutiveErrors)
    }

    @Test
    fun `incomplete message listing does not delete messages missing from it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val m1 = Message("m_1", "Anna", "Temat 1", t0, "Body 1", "<p>Body 1</p>", emptyList())
        val m2 = Message("m_2", "Anna", "Temat 2", t0, "Body 2", "<p>Body 2</p>", emptyList())
        val journal = FakeJournalClient().apply {
            headers = listOf(
                MessageHeader("m_1", "Anna", "Temat 1", t0, false),
                MessageHeader("m_2", "Anna", "Temat 2", t0, false),
            )
            messagesById = mapOf("m_1" to m1, "m_2" to m2)
        }
        val service = SyncService(journal = journal, store = store, clock = FakeClock(t0), ioDispatcher = dispatcher)
        assertEquals(SyncResult.Ok(2), service.syncNow())

        journal.headers = journal.headers.take(1)
        journal.headersComplete = false
        assertEquals(SyncResult.Ok(0), service.syncNow())

        assertEquals(2, store.listMessages().size)
    }

    @Test
    fun `credentials rejected during a later refresh stop all syncs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val journal = FakeJournalClient()
        val service = SyncService(journal = journal, store = store, clock = FakeClock(t0), ioDispatcher = dispatcher)
        assertTrue(service.syncNow() is SyncResult.Ok)

        journal.gradesError = AuthRejected("rejected")
        assertEquals(SyncResult.AuthFailed, service.syncNow())
        assertEquals(SyncResult.AuthFailed, service.syncNow())

        assertEquals(1, journal.loginCount)
        assertEquals(AuthState.AUTH_FAILED, service.state().authState)
        assertEquals(SyncRunStatus.AUTH_FAILED, store.getLastSyncRun()?.status)
    }

    @Test
    fun `cancellation is not recorded as an unexpected error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = openStore()
        val journal = FakeJournalClient().apply { gradesError = CancellationException("shutdown") }
        val service = SyncService(journal = journal, store = store, clock = FakeClock(t0), ioDispatcher = dispatcher)

        assertFailsWith<CancellationException> { service.syncNow() }

        assertEquals(0, service.state().consecutiveErrors)
        assertEquals("cancelled", store.getLastSyncRun()?.errorCode)
    }
}
