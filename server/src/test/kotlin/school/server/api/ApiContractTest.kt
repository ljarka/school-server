package school.server.api

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import school.model.Attachment
import school.model.AuthRejected
import school.model.ChangePageDto
import school.model.EntityType
import school.model.Grade
import school.model.GradeKind
import school.model.GradesListDto
import school.model.JournalClient
import school.model.MarkAllReadResponseDto
import school.model.Message
import school.model.MessageDetailDto
import school.model.MessageHeader
import school.model.MessagesPageDto
import school.model.StatusDto
import school.model.SubjectsListDto
import school.model.SyncRunStatus
import school.server.auth.FakeClock
import school.server.config.Config
import school.server.config.LogLevel
import school.server.config.Secret
import school.server.config.TrustProxy
import school.server.schoolModule
import school.server.store.DatabaseFactory
import school.server.store.Store
import school.server.store.db.SchoolDatabase
import school.server.sync.ChangeDetector
import school.server.sync.SyncService

class ApiContractTest {
    @TempDir
    lateinit var tempDir: File

    private val json = Json { ignoreUnknownKeys = false }
    private val t0 = Instant.parse("2026-11-16T12:00:00Z")

    private fun testConfig(dbPath: String) = Config(
        journalUrl = "https://portal.example.test",
        journalApiUrl = "https://api.example.test",
        journalMessagesUrl = "https://messages.example.test",
        child = null,
        login = "parent@example.test",
        password = Secret("secret"),
        apiKey = Secret("test-key"),
        syncIntervalMinutes = 15,
        trustProxy = TrustProxy.NONE,
        dbPath = dbPath,
        port = 5100,
        logLevel = LogLevel.INFO,
    )

    private fun openStore(name: String = "school.db"): Store {
        val dbFile = File(tempDir, name)
        val driver = DatabaseFactory.open(dbFile.absolutePath)
        return Store(SchoolDatabase(driver))
    }

    private fun seedStore(store: Store) {
        val detector = ChangeDetector()
        val runId = store.insertSyncRun(t0)

        val grades = listOf(
            Grade(
                "g_1",
                "Matematyka",
                "4+",
                4.5,
                3,
                "Sprawdzian",
                1,
                GradeKind.REGULAR,
                true,
                "Jan Kowalski",
                "Dobrze",
                LocalDate.parse("2026-11-10"),
            ),
            Grade(
                "g_2",
                "Matematyka",
                "5",
                5.0,
                1,
                "Kartkówka",
                1,
                GradeKind.REGULAR,
                true,
                "Jan Kowalski",
                null,
                LocalDate.parse("2026-11-12"),
            ),
            Grade(
                "g_3",
                "Język polski",
                "np",
                null,
                1,
                "Aktywność",
                1,
                GradeKind.REGULAR,
                false,
                "Anna Nowak",
                null,
                LocalDate.parse("2026-11-13"),
            ),
        )
        val messages = listOf(
            Message(
                "m_1",
                "Anna Nowak",
                "Wycieczka",
                Instant.parse("2026-11-15T10:00:00Z"),
                "Plan",
                "<p>Plan</p>",
                listOf(Attachment("plan.pdf")),
            ),
            Message(
                "m_2",
                "Sekretariat",
                "Zebranie",
                Instant.parse("2026-11-16T09:00:00Z"),
                "Zapraszamy",
                "<p>Zapraszamy</p>",
                emptyList(),
            ),
            Message(
                "m_3",
                "Dyrektor",
                "Ogłoszenie",
                Instant.parse("2026-11-16T11:00:00Z"),
                "Ważne",
                "<p>Ważne</p>",
                emptyList(),
            ),
        )

        val gc = detector.diffGrades(emptyList(), grades, complete = true)
        val mc = detector.diffMessages(emptyList(), messages, complete = true)
        val count = store.applyChanges(runId, gc, mc, t0)
        store.setMeta("auth_state", "ok")
        store.finishSyncRun(runId, t0, SyncRunStatus.OK, null, 6, count)
    }

    @Test
    fun `returns 503 NO_DATA_YET before any sync and serves openapi and docs without key`() = testApplication {
        val dbPath = File(tempDir, "empty.db").absolutePath
        val store = openStore("empty.db")
        application {
            schoolModule(testConfig(dbPath), store = store)
        }

        val gradesRes = client.get("/api/v1/grades") { header("X-School-Key", "test-key") }
        assertEquals(HttpStatusCode.ServiceUnavailable, gradesRes.status)
        assertTrue(gradesRes.bodyAsText().contains("NO_DATA_YET"))

        val statusRes = client.get("/api/v1/status") { header("X-School-Key", "test-key") }
        assertEquals(HttpStatusCode.OK, statusRes.status)
        val status = json.decodeFromString<StatusDto>(statusRes.bodyAsText())
        assertEquals("unknown", status.auth)
        assertNull(status.lastSync)

        val specRes = client.get("/api/v1/openapi.yaml")
        assertEquals(HttpStatusCode.OK, specRes.status)
        assertTrue(specRes.bodyAsText().contains("School Server API"))

        val docsRes = client.get("/docs")
        assertEquals(HttpStatusCode.OK, docsRes.status)
    }

    @Test
    fun `grades and subjects endpoints filter and compute weighted averages`() = testApplication {
        val dbPath = File(tempDir, "grades.db").absolutePath
        val store = openStore("grades.db").also { seedStore(it) }
        application {
            schoolModule(testConfig(dbPath), store = store)
        }

        val gradesRes = client.get("/api/v1/grades?subject=Matematyka&semester=1") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.OK, gradesRes.status)
        val grades = json.decodeFromString<GradesListDto>(gradesRes.bodyAsText())
        assertEquals(2, grades.items.size)
        assertEquals("g_2", grades.items[0].id)

        val badSemester = client.get("/api/v1/grades?semester=3") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.BadRequest, badSemester.status)
        assertTrue(badSemester.bodyAsText().contains("semester"))

        val subjectsRes = client.get("/api/v1/grades/subjects") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.OK, subjectsRes.status)
        val subjects = json.decodeFromString<SubjectsListDto>(subjectsRes.bodyAsText())
        assertEquals(listOf("Język polski", "Matematyka"), subjects.items.map { it.subject })
        assertNull(subjects.items[0].average)
        // (4.5 * 3 + 5.0 * 1) / 4 = 18.5 / 4 = 4.625 -> 4.63
        assertEquals(4.63, subjects.items[1].average)
    }

    @Test
    fun `messages paging, detail, and local read state survive restart`() = testApplication {
        val dbFile = File(tempDir, "msgs.db")
        val store = openStore("msgs.db").also { seedStore(it) }
        val clock = FakeClock(t0)
        application {
            schoolModule(testConfig(dbFile.absolutePath), clock = clock, store = store)
        }

        val page1Res = client.get("/api/v1/messages?limit=2") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.OK, page1Res.status)
        val page1 = json.decodeFromString<MessagesPageDto>(page1Res.bodyAsText())
        assertEquals(listOf("m_3", "m_2"), page1.items.map { it.id })
        assertNotNull(page1.nextCursor)
        assertFalse(page1Res.bodyAsText().contains("bodyText"))

        val page2Res = client.get("/api/v1/messages?limit=2&cursor=${page1.nextCursor}") {
            header("X-School-Key", "test-key")
        }
        val page2 = json.decodeFromString<MessagesPageDto>(page2Res.bodyAsText())
        assertEquals(listOf("m_1"), page2.items.map { it.id })
        assertNull(page2.nextCursor)

        val readRes = client.put("/api/v1/messages/m_1/read") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.OK, readRes.status)
        val readMsg = json.decodeFromString<MessageDetailDto>(readRes.bodyAsText())
        assertTrue(readMsg.read)
        assertEquals(t0, readMsg.readAt)

        // Verify persisted in a fresh Store instance on the same file
        val reopenedStore = openStore("msgs.db")
        assertEquals(t0, reopenedStore.getMessage("m_1")?.readAt)

        val unreadRes = client.delete("/api/v1/messages/m_1/read") {
            header("X-School-Key", "test-key")
        }
        assertFalse(json.decodeFromString<MessageDetailDto>(unreadRes.bodyAsText()).read)

        val readAllRes = client.post("/api/v1/messages/read-all") {
            header("X-School-Key", "test-key")
        }
        assertEquals(3L, json.decodeFromString<MarkAllReadResponseDto>(readAllRes.bodyAsText()).marked)

        val notFoundRes = client.get("/api/v1/messages/m_999") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.NotFound, notFoundRes.status)
    }

    @Test
    fun `changes feed and status endpoint match contract`() = testApplication {
        val dbPath = File(tempDir, "changes.db").absolutePath
        val store = openStore("changes.db").also { seedStore(it) }
        application {
            schoolModule(testConfig(dbPath), store = store)
        }

        val page1Res = client.get("/api/v1/changes?after=0&limit=4") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.OK, page1Res.status)
        val page1 = json.decodeFromString<ChangePageDto>(page1Res.bodyAsText())
        assertEquals(4, page1.items.size)
        assertTrue(page1.hasMore)

        val page2Res = client.get("/api/v1/changes?after=${page1.nextCursor}&limit=4") {
            header("X-School-Key", "test-key")
        }
        val page2 = json.decodeFromString<ChangePageDto>(page2Res.bodyAsText())
        assertEquals(2, page2.items.size)
        assertFalse(page2.hasMore)

        val emptyAfter = client.get("/api/v1/changes?after=${page2.nextCursor}") {
            header("X-School-Key", "test-key")
        }
        val emptyPage = json.decodeFromString<ChangePageDto>(emptyAfter.bodyAsText())
        assertTrue(emptyPage.items.isEmpty())
        assertEquals(page2.nextCursor, emptyPage.nextCursor)

        val onlyMessages = client.get("/api/v1/changes?type=message") {
            header("X-School-Key", "test-key")
        }
        val msgPage = json.decodeFromString<ChangePageDto>(onlyMessages.bodyAsText())
        assertEquals(3, msgPage.items.size)
        assertTrue(msgPage.items.all { it.entityType == EntityType.MESSAGE })

        val conflictParams = client.get("/api/v1/changes?after=0&since=2026-11-16T00:00:00Z") {
            header("X-School-Key", "test-key")
        }
        assertEquals(HttpStatusCode.BadRequest, conflictParams.status)

        val statusRes = client.get("/api/v1/status") {
            header("X-School-Key", "test-key")
        }
        val statusBody = statusRes.bodyAsText()
        val status = json.decodeFromString<StatusDto>(statusBody)
        assertEquals("ok", status.auth)
        assertEquals(6L, status.lastEventId)
        assertEquals(3L, status.counts.grades)
        assertEquals(3L, status.counts.messages)
        assertEquals(3L, status.counts.unreadMessages)
        assertFalse(statusBody.contains("parent@example.test"))
        assertFalse(statusBody.contains("portal.example.test"))
    }

    @Test
    fun `post sync returns 202 and 409 on auth_failed`() = testApplication {
        val dbPath = File(tempDir, "sync.db").absolutePath
        val store = openStore("sync.db")
        val failingJournal = object : JournalClient {
            override fun login() = throw AuthRejected("rejected")

            override fun fetchGrades(childId: String?): List<Grade> = emptyList()

            override fun fetchMessageHeaders(childId: String?): List<MessageHeader> = emptyList()

            override fun fetchMessage(sourceId: String, childId: String?): Message = error("unused")
        }
        val syncService = SyncService(journal = failingJournal, store = store, clock = FakeClock(t0))

        application {
            schoolModule(testConfig(dbPath), store = store, syncService = syncService)
        }

        val first = client.post("/api/v1/sync") { header("X-School-Key", "test-key") }
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals("""{"status":"started"}""", first.bodyAsText())

        // Trigger auth_failed synchronously then verify POST /sync returns 409 AUTH_FAILED
        syncService.syncNow()
        val afterAuthFail = client.post("/api/v1/sync") { header("X-School-Key", "test-key") }
        assertEquals(HttpStatusCode.Conflict, afterAuthFail.status)
        assertTrue(afterAuthFail.bodyAsText().contains("AUTH_FAILED"))
    }

    @Test
    fun `children endpoint and child query filter work across grades, messages, and changes`() = testApplication {
        val dbPath = File(tempDir, "multi_child.db").absolutePath
        val store = openStore("multi_child.db")
        store.upsertChildAccounts(
            listOf(
                school.model.ChildAccount(id = "101", login = "111u", name = "Ala Kowalska"),
                school.model.ChildAccount(id = "102", login = "222u", name = "Jan Kowalski"),
            ),
            now = t0,
        )
        val detector = ChangeDetector()
        val runId = store.insertSyncRun(t0)
        val g1 = Grade(
            "g_1",
            "Matematyka",
            "5",
            5.0,
            2,
            "Kartkówka",
            1,
            GradeKind.REGULAR,
            true,
            "Jan Kowalski",
            null,
            LocalDate.parse("2026-11-10"),
        )
        val m1 = Message("m_1", "Anna", "Temat 1", t0, "Treść 1", "<p>Treść 1</p>", emptyList())
        val g2 = Grade(
            "g_2",
            "Fizyka",
            "4",
            4.0,
            2,
            "Sprawdzian",
            1,
            GradeKind.REGULAR,
            true,
            "Jan Kowalski",
            null,
            LocalDate.parse("2026-11-11"),
        )
        val m2 = Message("m_2", "Jan", "Temat 2", t0, "Treść 2", "<p>Treść 2</p>", emptyList())

        store.applyChanges(
            syncRunId = runId,
            gradeChanges = detector.diffGrades(emptyList(), listOf(g1), complete = true),
            messageChanges = detector.diffMessages(emptyList(), listOf(m1), complete = true),
            now = t0,
            childId = "101",
        )
        store.applyChanges(
            syncRunId = runId,
            gradeChanges = detector.diffGrades(emptyList(), listOf(g2), complete = true),
            messageChanges = detector.diffMessages(emptyList(), listOf(m2), complete = true),
            now = t0,
            childId = "102",
        )
        store.finishSyncRun(runId, t0, school.model.SyncRunStatus.OK, null, 4, 4)

        application { schoolModule(testConfig(dbPath), store = store) }

        val childrenRes = client.get("/api/v1/children") { header("X-School-Key", "test-key") }
        assertEquals(HttpStatusCode.OK, childrenRes.status)
        val childrenBody = json.decodeFromString<school.model.ChildrenListDto>(childrenRes.bodyAsText())
        assertEquals(listOf("101", "102"), childrenBody.children.map { it.id })

        val child1Grades = client.get("/api/v1/grades?child=101") { header("X-School-Key", "test-key") }
        val child1GradesDto = json.decodeFromString<GradesListDto>(child1Grades.bodyAsText())
        assertEquals(listOf("g_1"), child1GradesDto.items.map { it.id })
        assertEquals("101", child1GradesDto.items.single().childId)

        val child2ByLogin = client.get("/api/v1/messages?child=222u") { header("X-School-Key", "test-key") }
        val child2MsgsDto = json.decodeFromString<MessagesPageDto>(child2ByLogin.bodyAsText())
        assertEquals(listOf("m_2"), child2MsgsDto.items.map { it.id })
        assertEquals("102", child2MsgsDto.items.single().childId)

        val child2Changes = client.get("/api/v1/changes?child=102") { header("X-School-Key", "test-key") }
        val child2ChangesDto = json.decodeFromString<ChangePageDto>(child2Changes.bodyAsText())
        assertEquals(listOf("g_2", "m_2"), child2ChangesDto.items.map { it.entityId })
    }
}
