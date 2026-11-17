package school.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.LocalDate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import school.model.Grade
import school.model.GradeKind
import school.model.JournalUnavailable

class HttpJournalClientGradesTest {
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

    private fun authFixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/auth/$name")?.readText())

    private fun gradeFixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/grades/$name")?.readText())

    private fun loggedInClient(): HttpJournalClient {
        server.enqueue(MockResponse(code = 200, body = authFixture("login_page.html")))
        server.enqueue(MockResponse(code = 200, body = "ok"))
        server.enqueue(MockResponse(code = 200, body = authFixture("me.json")))
        server.enqueue(MockResponse(code = 200, body = authFixture("accounts.json")))

        val base = server.url("/").toString().trimEnd('/')
        return HttpJournalClient(
            endpoints = JournalEndpoints(portal = base, api = base, messages = base),
            login = "parent@example.test",
            passwordProvider = { "secret" },
            minRequestDelayMs = 0L,
        ).apply { login() }
    }

    @Test
    fun `fetchGrades resolves lookups, comments, kinds, and Polish characters`() {
        val journal = loggedInClient()

        server.enqueue(MockResponse(code = 200, body = gradeFixture("grades.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("subjects.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("categories.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("comments.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("users.json")))

        val grades = journal.fetchGrades()
        assertEquals(6, grades.size)
        assertEquals(
            Grade(
                sourceId = "g_8812",
                subject = "Matematyka",
                value = "4+",
                numericValue = 4.5,
                weight = 3,
                category = "Sprawdzian",
                semester = 1,
                kind = GradeKind.REGULAR,
                countsToAverage = true,
                teacher = "Jan Kowalski",
                comment = "Świetna praca z ułamkami!",
                issuedOn = LocalDate.parse("2026-11-10"),
            ),
            grades[0],
        )
        assertEquals("Język polski", grades[1].subject)
        assertEquals("Anna Zielińska", grades[1].teacher)
        assertEquals(null, grades[1].numericValue)
        assertEquals(GradeKind.PROPOSED_SEMESTER, grades[2].kind)
        assertEquals(GradeKind.SEMESTER, grades[3].kind)
        assertEquals(GradeKind.PROPOSED_FINAL, grades[4].kind)
        assertEquals(GradeKind.FINAL, grades[5].kind)
    }

    @Test
    fun `empty grades array returns empty list`() {
        val journal = loggedInClient()
        server.enqueue(MockResponse(code = 200, body = gradeFixture("grades_empty.json")))

        assertEquals(emptyList(), journal.fetchGrades())
    }

    @Test
    fun `broken grades json throws JournalUnavailable layout_changed_grades`() {
        val journal = loggedInClient()
        server.enqueue(MockResponse(code = 200, body = gradeFixture("grades_broken.json")))

        val ex = assertFailsWith<JournalUnavailable> { journal.fetchGrades() }
        assertEquals("layout_changed:grades", ex.message)
    }

    @Test
    fun `gateway 403 AccessDeny on grades returns empty list without failing`() {
        val base = server.url("/").toString().trimEnd('/')
        val gatewayClient = HttpJournalClient(
            endpoints = JournalEndpoints(portal = base, api = "$base/3.0", messages = base, gateway = base),
            login = "1234567u",
            passwordProvider = { "secret" },
            minRequestDelayMs = 0L,
        )
        server.enqueue(MockResponse(code = 200, body = "ok")) // portal init
        server.enqueue(MockResponse(code = 200, body = "auth")) // GET OAuth/Authorization
        server.enqueue(MockResponse(code = 200, body = """{"status":"ok"}""")) // POST credentials
        server.enqueue(MockResponse(code = 200, body = "ok")) // 2FA -> Grant
        server.enqueue(MockResponse(code = 200, body = """{"UserIdentifier":"1234567u"}""")) // TokenInfo
        server.enqueue(MockResponse(code = 200, body = "{}")) // UserInfo
        server.enqueue(
            MockResponse(
                code = 403,
                body =
                """{"Status":"Error","Code":"AccessDeny",""" +
                    """"Message":"Account not have access to Grades resource"}""",
            ),
        )

        gatewayClient.login()
        assertEquals(emptyList(), gatewayClient.fetchGrades())
    }

    @Test
    fun `fetchGrades tolerates null user names`() {
        val journal = loggedInClient()

        server.enqueue(MockResponse(code = 200, body = gradeFixture("grades.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("subjects.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("categories.json")))
        server.enqueue(MockResponse(code = 200, body = """{"Comments":[{"Id":1,"Grade":{"Id":8812},"Text":null}]}"""))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"Users":[{"Id":33,"FirstName":null,"LastName":null}]}""",
            ),
        )

        val grades = journal.fetchGrades()
        assertEquals(6, grades.size)
        assertEquals("?", grades.first().teacher)
    }
}
