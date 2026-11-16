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
    @SerialName("Text") val text: String = "",
)

@Serializable
internal data class GradeCommentsResponseDto(@SerialName("Comments") val comments: List<GradeCommentDto> = emptyList())

@Serializable
internal data class UserDto(
    @SerialName("Id") val id: Long,
    @SerialName("FirstName") val firstName: String = "",
    @SerialName("LastName") val lastName: String = "",
)

@Serializable
internal data class UsersResponseDto(@SerialName("Users") val users: List<UserDto> = emptyList())
