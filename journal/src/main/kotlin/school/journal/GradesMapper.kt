package school.journal

import kotlinx.datetime.LocalDate
import org.slf4j.LoggerFactory
import school.model.Grade
import school.model.GradeKind
import school.model.JournalUnavailable

internal object GradesMapper {
    private val logger = LoggerFactory.getLogger(GradesMapper::class.java)

    fun map(
        grades: List<GradeDto>,
        subjects: Map<Long, SubjectDto>,
        categories: Map<Long, GradeCategoryDto>,
        commentsByGradeId: Map<Long, String>,
        users: Map<Long, UserDto>,
    ): List<Grade> = grades.map { dto ->
        val subjectName = subjects[dto.subject.id]?.name ?: run {
            logger.debug("e-journal missing subject lookup id={}", dto.subject.id)
            "?"
        }
        val category = categories[dto.category.id] ?: run {
            logger.debug("e-journal missing category lookup id={}", dto.category.id)
            null
        }
        val teacher = users[dto.addedBy.id]?.let { user ->
            listOf(user.firstName.trim(), user.lastName.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifEmpty { "?" }
        } ?: run {
            logger.debug("e-journal missing user lookup id={}", dto.addedBy.id)
            "?"
        }

        val issuedOn = try {
            LocalDate.parse(dto.date)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:grades", e)
        }

        Grade(
            sourceId = "g_${dto.id}",
            subject = subjectName,
            value = dto.grade,
            numericValue = parseNumericGrade(dto.grade),
            weight = category?.weight,
            category = category?.name ?: "?",
            semester = dto.semester,
            kind = resolveKind(dto),
            countsToAverage = category?.countToTheAverage ?: false,
            teacher = teacher,
            comment = commentsByGradeId[dto.id],
            issuedOn = issuedOn,
        )
    }

    fun resolveKind(dto: GradeDto): GradeKind = when {
        dto.isFinal -> GradeKind.FINAL
        dto.isFinalProposition -> GradeKind.PROPOSED_FINAL
        dto.isSemester -> GradeKind.SEMESTER
        dto.isSemesterProposition -> GradeKind.PROPOSED_SEMESTER
        else -> GradeKind.REGULAR
    }

    fun parseNumericGrade(value: String): Double? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null

        val match = NUMERIC_GRADE_REGEX.matchEntire(trimmed) ?: return null
        val base = match.groupValues[1].toDouble()
        return when (match.groupValues[2]) {
            "+" -> base + 0.5
            "-" -> base - 0.25
            else -> base
        }
    }

    private val NUMERIC_GRADE_REGEX = Regex("^([1-6])([+-]?)$")
}
