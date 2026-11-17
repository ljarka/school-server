package school.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class ApiRefDto(@SerialName("Id") val id: Long)

@Serializable
internal data class GradeDto(
    @SerialName("Id") val id: Long,
    @SerialName("Subject") val subject: ApiRefDto,
    @SerialName("Category") val category: ApiRefDto,
    @SerialName("AddedBy") val addedBy: ApiRefDto,
    @SerialName("Grade") val grade: String,
    @SerialName("Date") val date: String,
    @SerialName("Semester") val semester: Int,
    @SerialName("IsConstituent") val isConstituent: Boolean = true,
    @SerialName("IsSemester") val isSemester: Boolean = false,
    @SerialName("IsSemesterProposition") val isSemesterProposition: Boolean = false,
    @SerialName("IsFinal") val isFinal: Boolean = false,
    @SerialName("IsFinalProposition") val isFinalProposition: Boolean = false,
)

@Serializable
internal data class GradesResponseDto(@SerialName("Grades") val grades: List<GradeDto>)

@Serializable
internal data class SubjectDto(@SerialName("Id") val id: Long, @SerialName("Name") val name: String)

@Serializable
internal data class SubjectsResponseDto(@SerialName("Subjects") val subjects: List<SubjectDto> = emptyList())

@Serializable
internal data class LessonDto(@SerialName("Id") val id: Long, @SerialName("Subject") val subject: ApiRefDto? = null)

@Serializable
internal data class LessonsResponseDto(@SerialName("Lessons") val lessons: List<LessonDto> = emptyList())

@Serializable
internal data class GradeCategoryDto(
    @SerialName("Id") val id: Long,
    @SerialName("Name") val name: String,
    @SerialName("Weight") val weight: Int? = null,
    @SerialName("CountToTheAverage") val countToTheAverage: Boolean = false,
)

@Serializable
internal data class GradeCategoriesResponseDto(
    @SerialName("Categories") val categories: List<GradeCategoryDto> = emptyList(),
)

@Serializable
internal data class GradeCommentDto(
    @SerialName("Id") val id: Long,
    @SerialName("Grade") val grade: ApiRefDto? = null,
    @SerialName("Text") val text: String? = null,
)

@Serializable
internal data class GradeCommentsResponseDto(@SerialName("Comments") val comments: List<GradeCommentDto> = emptyList())

@Serializable
internal data class UserDto(
    @SerialName("Id") val id: Long,
    @SerialName("AccountId") val accountId: String? = null,
    @SerialName("FirstName") val firstName: String? = null,
    @SerialName("LastName") val lastName: String? = null,
)

@Serializable
internal data class UsersResponseDto(@SerialName("Users") val users: List<UserDto> = emptyList())

@Serializable
internal data class DescriptiveGradeDto(
    @SerialName("Id") val id: Long,
    @SerialName("Subject") val subject: ApiRefDto? = null,
    @SerialName("Lesson") val lesson: ApiRefDto? = null,
    @SerialName("Skill") val skill: ApiRefDto? = null,
    @SerialName("Category") val category: ApiRefDto? = null,
    @SerialName("AddedBy") val addedBy: ApiRefDto? = null,
    @SerialName("Map") val map: JsonPrimitive? = null,
    @SerialName("RealGradeValue") val realGradeValue: JsonPrimitive? = null,
    @SerialName("Grade") val grade: JsonPrimitive? = null,
    @SerialName("Date") val date: String = "",
    @SerialName("Semester") val semester: Int = 1,
    @SerialName("Comments") val comments: JsonElement? = null,
    @SerialName("IsSemester") val isSemester: Boolean = false,
    @SerialName("IsSemesterProposition") val isSemesterProposition: Boolean = false,
    @SerialName("IsFinal") val isFinal: Boolean = false,
    @SerialName("IsFinalProposition") val isFinalProposition: Boolean = false,
) {
    fun resolvedValue(): String = (
        map?.content?.takeIf { it != "null" && it.isNotBlank() }
            ?: realGradeValue?.content?.takeIf { it != "null" && it.isNotBlank() }
            ?: ""
        ).trim()

    fun commentIds(): List<Long> = extractCommentIds(comments)
    fun inlineCommentTexts(): List<String> = extractInlineCommentTexts(comments)
}

@Serializable
internal data class DescriptiveGradesResponseDto(
    @SerialName("Grades") val grades: List<DescriptiveGradeDto> = emptyList(),
    @SerialName("DescriptiveGrades") val descriptiveGrades: List<DescriptiveGradeDto> = emptyList(),
) {
    fun resolved(): List<DescriptiveGradeDto> = grades.ifEmpty { descriptiveGrades }
}

@Serializable
internal data class DescriptiveSkillDto(
    @SerialName("Id") val id: Long,
    @SerialName("Name") val name: String = "",
    @SerialName("Subject") val subject: ApiRefDto? = null,
    @SerialName("Weight") val weight: Int? = null,
    @SerialName("CountToTheAverage") val countToTheAverage: Boolean = false,
)

@Serializable
internal data class DescriptiveSkillsResponseDto(
    @SerialName("Skills") val skills: List<DescriptiveSkillDto> = emptyList(),
)

@Serializable
internal data class TextGradeDto(
    @SerialName("Id") val id: Long,
    @SerialName("Subject") val subject: ApiRefDto? = null,
    @SerialName("Category") val category: ApiRefDto? = null,
    @SerialName("Skill") val skill: ApiRefDto? = null,
    @SerialName("AddedBy") val addedBy: ApiRefDto? = null,
    @SerialName("Grade") val grade: String = "",
    @SerialName("Date") val date: String = "",
    @SerialName("Semester") val semester: Int = 1,
    @SerialName("ShowInGradesView") val showInGradesView: Boolean = true,
    @SerialName("IsSemester") val isSemester: Boolean = false,
    @SerialName("IsSemesterProposition") val isSemesterProposition: Boolean = false,
    @SerialName("IsFinal") val isFinal: Boolean = false,
    @SerialName("IsFinalProposition") val isFinalProposition: Boolean = false,
)

@Serializable
internal data class TextGradesResponseDto(
    @SerialName("Grades") val grades: List<TextGradeDto> = emptyList(),
    @SerialName("TextGrades") val textGrades: List<TextGradeDto> = emptyList(),
    @SerialName("BaseTextGrades") val baseTextGrades: List<TextGradeDto> = emptyList(),
) {
    fun resolved(): List<TextGradeDto> = grades.ifEmpty { textGrades }.ifEmpty { baseTextGrades }
}

@Serializable
internal data class PointGradeDto(
    @SerialName("Id") val id: Long,
    @SerialName("Subject") val subject: ApiRefDto? = null,
    @SerialName("Lesson") val lesson: ApiRefDto? = null,
    @SerialName("Category") val category: ApiRefDto? = null,
    @SerialName("AddedBy") val addedBy: ApiRefDto? = null,
    @SerialName("Grade") val grade: String = "",
    @SerialName("GradeValue") val gradeValue: Double? = null,
    @SerialName("Date") val date: String = "",
    @SerialName("Semester") val semester: Int = 1,
    @SerialName("Comments") val comments: JsonElement? = null,
) {
    fun commentIds(): List<Long> = extractCommentIds(comments)
    fun inlineCommentTexts(): List<String> = extractInlineCommentTexts(comments)
}

internal fun extractCommentIds(element: JsonElement?): List<Long> = when (element) {
    null -> emptyList()
    is JsonPrimitive -> listOfNotNull(element.longOrNull)
    is JsonObject -> listOfNotNull((element["Id"] ?: element["id"])?.let { (it as? JsonPrimitive)?.longOrNull })
    is JsonArray -> element.flatMap(::extractCommentIds)
}

internal fun extractInlineCommentTexts(element: JsonElement?): List<String> = when (element) {
    null -> emptyList()
    is JsonPrimitive -> emptyList()
    is JsonObject -> {
        val text = (element["Text"] ?: element["text"] ?: element["content"]) as? JsonPrimitive
        listOfNotNull(text?.content?.trim()?.takeIf { it.isNotEmpty() && text.isString })
    }
    is JsonArray -> element.flatMap(::extractInlineCommentTexts)
}

@Serializable
internal data class PointGradesResponseDto(
    @SerialName("Grades") val grades: List<PointGradeDto> = emptyList(),
    @SerialName("PointGrades") val pointGrades: List<PointGradeDto> = emptyList(),
) {
    fun resolved(): List<PointGradeDto> = grades.ifEmpty { pointGrades }
}

@Serializable
internal data class PointGradeCategoryDto(
    @SerialName("Id") val id: Long,
    @SerialName("Name") val name: String = "",
    @SerialName("Weight") val weight: Int? = null,
    @SerialName("CountToTheAverage") val countToTheAverage: Boolean = false,
    @SerialName("ValueTo") val valueTo: Double? = null,
)

@Serializable
internal data class PointGradeCategoriesResponseDto(
    @SerialName("Categories") val categories: List<PointGradeCategoryDto> = emptyList(),
)

@Serializable
internal data class AuthUserInfoPayloadDto(
    @SerialName("IdentifierOfStudentAssignedWithUser") val studentIdentifier: String = "",
)

@Serializable
internal data class AuthUserInfoResponseDto(
    @SerialName("UserInfo") val userInfo: AuthUserInfoPayloadDto? = null,
    @SerialName("IdentifierOfStudentAssignedWithUser") val studentIdentifier: String = "",
) {
    fun resolvedStudentIdentifier(): String? =
        (userInfo?.studentIdentifier?.ifBlank { null } ?: studentIdentifier.ifBlank { null })
}

@Serializable
internal data class AuthSubjectDto(
    @SerialName("identifier") val identifier: String = "",
    @SerialName("numericIdentifier") val numericIdentifier: Long? = null,
    @SerialName("name") val name: String? = null,
)

@Serializable
internal data class AuthSubjectsResponseDto(@SerialName("data") val data: List<AuthSubjectDto> = emptyList())

@Serializable
internal data class PartialGradeAreaDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
)

@Serializable
internal data class PartialGradeScaleValueDto(
    @SerialName("id") val id: String = "",
    @SerialName("value") val value: String = "",
)

@Serializable
internal data class PartialGradeRequirementDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
)

@Serializable
internal data class PartialGradeDto(
    @SerialName("gradeId") val gradeId: String = "",
    @SerialName("subjectId") val subjectId: String = "",
    @SerialName("teacherId") val teacherId: String? = null,
    @SerialName("addedBy") val addedBy: String? = null,
    @SerialName("area") val area: PartialGradeAreaDto? = null,
    @SerialName("scaleValue") val scaleValue: PartialGradeScaleValueDto? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("comments") val comments: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("implementedRequirements") val implementedRequirements: List<PartialGradeRequirementDto> = emptyList(),
    @SerialName("date") val date: String = "",
    @SerialName("semester") val semester: Int = 1,
)

@Serializable
internal data class PartialGradesResponseDto(@SerialName("data") val data: List<PartialGradeDto> = emptyList())
