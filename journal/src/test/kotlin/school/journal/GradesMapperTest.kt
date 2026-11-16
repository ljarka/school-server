package school.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import school.model.Grade
import school.model.GradeKind

class GradesMapperTest {
    @Test
    fun `parseNumericGrade maps numeric, plus, minus, and non-numeric values`() {
        val cases = listOf(
            "1" to 1.0,
            "2-" to 1.75,
            "3+" to 3.5,
            "3-" to 2.75,
            "4+" to 4.5,
            "5" to 5.0,
            "6-" to 5.75,
            "np" to null,
            "+" to null,
            "-" to null,
            "bz" to null,
            "" to null,
        )

        for ((raw, expected) in cases) {
            assertEquals(expected, GradesMapper.parseNumericGrade(raw), "Failed for '$raw'")
        }
    }

    @Test
    fun `maps missing lookup entries to neutral placeholder instead of dropping grade`() {
        val dto = GradeDto(
            id = 42,
            subject = ApiRefDto(999),
            category = ApiRefDto(888),
            addedBy = ApiRefDto(777),
            grade = "5",
            date = "2026-11-16",
            semester = 1,
        )

        val mapped = GradesMapper.map(
            grades = listOf(dto),
            subjects = emptyMap(),
            categories = emptyMap(),
            commentsByGradeId = emptyMap(),
            users = emptyMap(),
        )

        assertEquals(
            listOf(
                Grade(
                    sourceId = "g_42",
                    subject = "?",
                    value = "5",
                    numericValue = 5.0,
                    weight = null,
                    category = "?",
                    semester = 1,
                    kind = GradeKind.REGULAR,
                    countsToAverage = false,
                    teacher = "?",
                    comment = null,
                    issuedOn = LocalDate.parse("2026-11-16"),
                ),
            ),
            mapped,
        )
    }

    @Test
    fun `resolveKind maps all API flags`() {
        val base = GradeDto(
            id = 1,
            subject = ApiRefDto(1),
            category = ApiRefDto(1),
            addedBy = ApiRefDto(1),
            grade = "5",
            date = "2026-11-16",
            semester = 1,
        )
        assertEquals(GradeKind.REGULAR, GradesMapper.resolveKind(base))
        assertEquals(GradeKind.PROPOSED_SEMESTER, GradesMapper.resolveKind(base.copy(isSemesterProposition = true)))
        assertEquals(GradeKind.SEMESTER, GradesMapper.resolveKind(base.copy(isSemester = true)))
        assertEquals(GradeKind.PROPOSED_FINAL, GradesMapper.resolveKind(base.copy(isFinalProposition = true)))
        assertEquals(GradeKind.FINAL, GradesMapper.resolveKind(base.copy(isFinal = true)))
        assertNull(GradesMapper.parseNumericGrade("zw"))
    }
}
