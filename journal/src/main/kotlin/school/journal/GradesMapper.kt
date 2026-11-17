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
            listOfNotNull(user.firstName?.trim(), user.lastName?.trim())
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

    fun mapDescriptive(
        grades: List<DescriptiveGradeDto>,
        subjects: Map<Long, SubjectDto>,
        skills: Map<Long, DescriptiveSkillDto>,
        commentsById: Map<Long, String>,
        commentsByGradeId: Map<Long, String>,
        users: Map<Long, UserDto>,
    ): List<Grade> = grades.map { dto ->
        val skill = dto.skill?.let { skills[it.id] }
        val subjectId = dto.subject?.id ?: skill?.subject?.id
        val subjectName = subjectId?.let { subjects[it]?.name } ?: "?"
        val rawValue = (dto.map ?: dto.realGradeValue ?: dto.grade).orEmpty().trim()
        val (value, extraComment) = shortenValue(rawValue)
        val inlineComments = dto.comments.mapNotNull { commentsById[it.id]?.trim()?.takeIf(String::isNotEmpty) }
        val combinedComment = (
            listOfNotNull(extraComment) +
                inlineComments +
                listOfNotNull(commentsByGradeId[dto.id]?.takeIf { it !in inlineComments })
            ).joinToString("\n").ifEmpty { null }
        val kind = resolveKindFlags(
            isFinal = dto.isFinal,
            isFinalProposition = dto.isFinalProposition,
            isSemester = dto.isSemester,
            isSemesterProposition = dto.isSemesterProposition,
        )

        Grade(
            sourceId = "dg_${dto.id}",
            subject = subjectName,
            value = value.ifEmpty { "—" },
            numericValue = parseNumericGrade(value),
            weight = skill?.weight,
            category = skill?.name?.takeIf { it.isNotBlank() } ?: "?",
            semester = dto.semester,
            kind = kind,
            countsToAverage = false,
            teacher = resolveTeacher(dto.addedBy?.id, users),
            comment = combinedComment,
            issuedOn = parseIssuedOn(dto.date),
        )
    }

    fun mapText(
        grades: List<TextGradeDto>,
        prefix: String,
        subjects: Map<Long, SubjectDto>,
        categories: Map<Long, GradeCategoryDto>,
        skills: Map<Long, DescriptiveSkillDto>,
        users: Map<Long, UserDto>,
    ): List<Grade> = grades.filter { it.showInGradesView }.map { dto ->
        val category = dto.category?.let { categories[it.id] }
        val skill = dto.skill?.let { skills[it.id] }
        val subjectId = dto.subject?.id ?: skill?.subject?.id
        val subjectName = subjectId?.let { subjects[it]?.name } ?: "?"
        val collapsed = dto.grade.trim().replace(WHITESPACE_REGEX, " ")
        val (value, extraComment) = shortenValue(collapsed)
        val kind = resolveKindFlags(
            isFinal = dto.isFinal,
            isFinalProposition = dto.isFinalProposition,
            isSemester = dto.isSemester,
            isSemesterProposition = dto.isSemesterProposition,
        )

        Grade(
            sourceId = "${prefix}_${dto.id}",
            subject = subjectName,
            value = value.ifEmpty { "—" },
            numericValue = parseNumericGrade(value),
            weight = category?.weight ?: skill?.weight,
            category = category?.name?.takeIf { it.isNotBlank() }
                ?: skill?.name?.takeIf { it.isNotBlank() }
                ?: "Ocena opisowa",
            semester = dto.semester,
            kind = kind,
            countsToAverage = false,
            teacher = resolveTeacher(dto.addedBy?.id, users),
            comment = extraComment ?: collapsed.takeIf { it.length > MAX_CHIP_VALUE_LENGTH },
            issuedOn = parseIssuedOn(dto.date),
        )
    }

    fun mapPoint(
        grades: List<PointGradeDto>,
        subjects: Map<Long, SubjectDto>,
        categories: Map<Long, PointGradeCategoryDto>,
        commentsById: Map<Long, String>,
        commentsByGradeId: Map<Long, String>,
        users: Map<Long, UserDto>,
    ): List<Grade> = grades.map { dto ->
        val category = dto.category?.let { categories[it.id] }
        val subjectName = dto.subject?.let { subjects[it.id]?.name } ?: "?"
        val maxPoints = category?.valueTo
        val pointsText = dto.grade.trim().ifEmpty { dto.gradeValue?.let(::formatPoints).orEmpty() }
        val value = if (maxPoints != null && maxPoints > 0 && !pointsText.contains("/")) {
            "$pointsText/${formatPoints(maxPoints)}"
        } else {
            pointsText.ifEmpty { "—" }
        }
        val inlineComments = dto.comments.mapNotNull { commentsById[it.id]?.trim()?.takeIf(String::isNotEmpty) }
        val combinedComment = (
            inlineComments +
                listOfNotNull(commentsByGradeId[dto.id]?.takeIf { it !in inlineComments })
            ).joinToString("\n").ifEmpty { null }

        Grade(
            sourceId = "pg_${dto.id}",
            subject = subjectName,
            value = value,
            numericValue = null,
            weight = category?.weight,
            category = category?.name?.takeIf { it.isNotBlank() } ?: "?",
            semester = dto.semester,
            kind = GradeKind.REGULAR,
            countsToAverage = false,
            teacher = resolveTeacher(dto.addedBy?.id, users),
            comment = combinedComment,
            issuedOn = parseIssuedOn(dto.date),
        )
    }

    private fun shortenValue(raw: String): Pair<String, String?> {
        if (raw.length <= MAX_CHIP_VALUE_LENGTH) return raw to null
        return (raw.take(MAX_CHIP_VALUE_LENGTH - 1).trimEnd() + "…") to raw
    }

    private fun formatPoints(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun resolveTeacher(id: Long?, users: Map<Long, UserDto>): String = id?.let { users[it] }?.let { user ->
        listOfNotNull(user.firstName?.trim(), user.lastName?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "?" }
    } ?: "?"

    private fun parseIssuedOn(raw: String): LocalDate = try {
        LocalDate.parse(raw.trim().take(10))
    } catch (e: Exception) {
        throw JournalUnavailable("layout_changed:grades", e)
    }

    fun resolveKind(dto: GradeDto): GradeKind = resolveKindFlags(
        isFinal = dto.isFinal,
        isFinalProposition = dto.isFinalProposition,
        isSemester = dto.isSemester,
        isSemesterProposition = dto.isSemesterProposition,
    )

    private fun resolveKindFlags(
        isFinal: Boolean,
        isFinalProposition: Boolean,
        isSemester: Boolean,
        isSemesterProposition: Boolean,
    ): GradeKind = when {
        isFinal -> GradeKind.FINAL
        isFinalProposition -> GradeKind.PROPOSED_FINAL
        isSemester -> GradeKind.SEMESTER
        isSemesterProposition -> GradeKind.PROPOSED_SEMESTER
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

    private const val MAX_CHIP_VALUE_LENGTH = 12
    private val NUMERIC_GRADE_REGEX = Regex("^([1-6])([+-]?)$")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
