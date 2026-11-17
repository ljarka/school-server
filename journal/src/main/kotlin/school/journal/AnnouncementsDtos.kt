package school.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One notice-board entry from the JSON API. Only `Id` is guaranteed by the reference SDK's schema. */
@Serializable
internal data class SchoolNoticeDto(
    @SerialName("Id") val id: JsonElement? = null,
    @SerialName("Subject") val subject: String? = null,
    @SerialName("Content") val content: String? = null,
    @SerialName("StartDate") val startDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("CreationDate") val creationDate: String? = null,
    @SerialName("AddedBy") val addedBy: ApiRefDto? = null,
)

@Serializable
internal data class SchoolNoticesResponseDto(@SerialName("SchoolNotices") val notices: List<SchoolNoticeDto>)
