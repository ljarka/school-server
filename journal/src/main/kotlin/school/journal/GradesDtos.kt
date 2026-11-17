package school.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("FirstName") val firstName: String? = null,
    @SerialName("LastName") val lastName: String? = null,
)

@Serializable
internal data class UsersResponseDto(@SerialName("Users") val users: List<UserDto> = emptyList())

@Serializable
internal data class DescriptiveGradeDto(
    @SerialName("Id") val id: Long,
    @SerialName("Subject") val subject: ApiRefDto? = null,
    @SerialName("Skill") val skill: ApiRefDto? = null,
    @SerialName("Category") val category: ApiRefDto? = null,
    @SerialName("AddedBy") val addedBy: ApiRefDto? = null,
    @SerialName("Map") val map: String? = null,
    @SerialName("RealGradeValue") val realGradeValue: String? = null,
    @SerialName("Grade") val grade: String? = null,
    @SerialName("Date") val date: String = "",
    @SerialName("Semester") val semester: Int = 1,
    @SerialName("Comments") val comments: List<ApiRefDto> = emptyList(),
    @SerialName("IsSemester") val isSemester: Boolean = false,
    @SerialName("IsSemesterProposition") val isSemesterProposition: Boolean = false,
    @SerialName("IsFinal") val isFinal: Boolean = false,
    @SerialName("IsFinalProposition") val isFinalProposition: Boolean = false,
)

@Serializable
internal data class DescriptiveGradesResponseDto(
    @SerialName("Grades") val grades: List<DescriptiveGradeDto> = emptyList(),
)

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
internal data class TextGradesResponseDto(@SerialName("Grades") val grades: List<TextGradeDto> = emptyList())

@Serializable
internal data class PointGradeDto(
    @SerialName("Id") val id: Long,
    @SerialName("Subject") val subject: ApiRefDto? = null,
    @SerialName("Category") val category: ApiRefDto? = null,
    @SerialName("AddedBy") val addedBy: ApiRefDto? = null,
    @SerialName("Grade") val grade: String = "",
    @SerialName("GradeValue") val gradeValue: Double? = null,
    @SerialName("Date") val date: String = "",
    @SerialName("Semester") val semester: Int = 1,
    @SerialName("Comments") val comments: List<ApiRefDto> = emptyList(),
)

@Serializable
internal data class PointGradesResponseDto(@SerialName("Grades") val grades: List<PointGradeDto> = emptyList())

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
