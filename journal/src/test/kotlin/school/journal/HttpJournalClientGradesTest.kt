package school.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    private fun enqueueEmptyExtraGrades(count: Int = 5) {
        repeat(count) { server.enqueue(MockResponse(code = 404, body = "")) }
    }

    @Test
    fun `fetchGrades resolves lookups, comments, kinds, and Polish characters`() {
        val journal = loggedInClient()

        server.enqueue(MockResponse(code = 200, body = gradeFixture("grades.json")))
        enqueueEmptyExtraGrades()
        server.enqueue(MockResponse(code = 200, body = gradeFixture("subjects.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("users.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("categories.json")))
        server.enqueue(MockResponse(code = 200, body = gradeFixture("comments.json")))

        val grades = journal.fetchGrades()!!
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
        enqueueEmptyExtraGrades()

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
    fun `gateway 403 AccessDeny on grades returns null so stored grades are kept`() {
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
        enqueueEmptyExtraGrades()

        gatewayClient.login()
        assertNull(gatewayClient.fetchGrades())
    }

    @Test
    fun `fetchGrades tolerates null user names`() {
        val journal = loggedInClient()

        server.enqueue(MockResponse(code = 200, body = gradeFixture("grades.json")))
        enqueueEmptyExtraGrades()
        server.enqueue(MockResponse(code = 200, body = gradeFixture("subjects.json")))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"Users":[{"Id":33,"FirstName":null,"LastName":null}]}""",
            ),
        )
        server.enqueue(MockResponse(code = 200, body = gradeFixture("categories.json")))
        server.enqueue(MockResponse(code = 200, body = """{"Comments":[{"Id":1,"Grade":{"Id":8812},"Text":null}]}"""))

        val grades = journal.fetchGrades()!!
        assertEquals(6, grades.size)
        assertEquals("?", grades.first().teacher)
    }

    @Test
    fun `fetchGrades returns null when portal API returns AccessDeny`() {
        val journal = loggedInClient()
        server.enqueue(
            MockResponse(
                code = 403,
                body =
                """{"Status":"Error","Code":"AccessDeny",""" +
                    """"Message":"Account not have access to Grades resource"}""",
            ),
        )
        enqueueEmptyExtraGrades()
        assertNull(journal.fetchGrades())
    }

    @Test
    fun `fetchGrades maps descriptive, text, and point grades when present`() {
        val journal = loggedInClient()
        // /Grades denied (early primary school), /DescriptiveGrades and /BaseTextGrades populated
        server.enqueue(
            MockResponse(
                code = 403,
                body = """{"Status":"Error","Code":"AccessDeny","Message":"No access"}""",
            ),
        )
        // /DescriptiveGrades
        server.enqueue(
            MockResponse(
                code = 200,
                body =
                """{"Grades":[{"Id":501,"Subject":{"Id":1},"Skill":{"Id":90},"AddedBy":{"Id":20},""" +
                    """"Map":"6","Grade":"3","Date":"2026-11-12","Semester":1,"Comments":[{"Id":700}]}]}""",
            ),
        )
        // /DescriptiveTextGrades (404)
        server.enqueue(MockResponse(code = 404, body = ""))
        // /BaseTextGrades
        server.enqueue(
            MockResponse(
                code = 200,
                body =
                """{"Grades":[{"Id":601,"Subject":{"Id":2},"Category":{"Id":91},"AddedBy":{"Id":21},""" +
                    """"Grade":"Bardzo ładnie czyta z podziałem na role","Date":"2026-11-14","Semester":1}]}""",
            ),
        )
        // /TextGrades (404)
        server.enqueue(MockResponse(code = 404, body = ""))
        // /PointGrades
        server.enqueue(
            MockResponse(
                code = 200,
                body =
                """{"Grades":[{"Id":701,"Subject":{"Id":1},"Category":{"Id":92},"AddedBy":{"Id":20},""" +
                    """"Grade":"18","GradeValue":18.0,"Date":"2026-11-15","Semester":1}]}""",
            ),
        )
        // /Subjects
        server.enqueue(MockResponse(code = 200, body = gradeFixture("subjects.json")))
        // /Users
        server.enqueue(MockResponse(code = 200, body = gradeFixture("users.json")))
        // /DescriptiveGrades/Skills
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"Skills":[{"Id":90,"Name":"Czytanie ze zrozumieniem","Subject":{"Id":1}}]}""",
            ),
        )
        // /DescriptiveGrades/Comments
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"Comments":[{"Id":700,"Text":"Brawo!"}]}""",
            ),
        )
        // /TextGrades/Categories
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"Categories":[{"Id":91,"Name":"Aktywność","CountToTheAverage":false}]}""",
            ),
        )
        // /PointGrades/Categories
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"Categories":[{"Id":92,"Name":"Kartkówka","Weight":2,"ValueTo":20.0}]}""",
            ),
        )

        val grades = journal.fetchGrades()!!
        assertEquals(3, grades.size)
        val (dg, tg, pg) = grades
        assertEquals("dg_501", dg.sourceId)
        assertEquals("Matematyka", dg.subject)
        assertEquals("6", dg.value)
        assertEquals(6.0, dg.numericValue)
        assertEquals("Czytanie ze zrozumieniem", dg.category)
        assertEquals("Brawo!", dg.comment)

        assertEquals("tg_601", tg.sourceId)
        assertEquals("Język polski", tg.subject)
        assertEquals("Bardzo ładn…", tg.value)
        assertEquals("Bardzo ładnie czyta z podziałem na role", tg.comment)
        assertEquals("Aktywność", tg.category)

        assertEquals("pg_701", pg.sourceId)
        assertEquals("18/20", pg.value)
        assertEquals("Kartkówka", pg.category)
        assertEquals(2, pg.weight)
    }
}
