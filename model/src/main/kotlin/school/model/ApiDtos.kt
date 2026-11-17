package school.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChildAccountDto(val id: String, val login: String, val name: String)

@Serializable
data class ChildrenListDto(val children: List<ChildAccountDto>)

@Serializable
data class GradeDto(
    val id: String,
    val childId: String = "default",
    val subject: String,
    val value: String,
    val numericValue: Double?,
    val weight: Int?,
    val category: String,
    val semester: Int,
    val kind: GradeKind,
    val countsToAverage: Boolean,
    val teacher: String,
    val comment: String?,
    val issuedOn: LocalDate,
    val firstSeenAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

@Serializable
data class GradesListDto(val items: List<GradeDto>)

@Serializable
data class SubjectSummaryDto(val subject: String, val average: Double?, val grades: List<GradeDto>)

@Serializable
data class SubjectsListDto(val items: List<SubjectSummaryDto>)

@Serializable
data class MessageSummaryDto(
    val id: String,
    val childId: String = "default",
    val sender: String,
    val subject: String,
    val sentAt: Instant,
    val hasAttachments: Boolean,
    val read: Boolean,
    val readAt: Instant?,
    val deletedAt: Instant?,
    val type: MessageType = MessageType.MESSAGE,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
)

@Serializable
data class MessagesPageDto(val items: List<MessageSummaryDto>, val nextCursor: String?)

@Serializable
data class MessageDetailDto(
    val id: String,
    val childId: String = "default",
    val sender: String,
    val subject: String,
    val sentAt: Instant,
    val hasAttachments: Boolean,
    val read: Boolean,
    val readAt: Instant?,
    val deletedAt: Instant?,
    val type: MessageType = MessageType.MESSAGE,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val bodyText: String,
    val bodyHtml: String,
    val attachments: List<Attachment>,
)

@Serializable
data class MarkAllReadResponseDto(val marked: Long)

@Serializable
data class SyncStartedResponseDto(val status: String = "started")

@Serializable
data class ChangeEventDto(
    val id: Long,
    val childId: String = "default",
    val entityType: EntityType,
    val entityId: String,
    val changeType: ChangeType,
    val detectedAt: Instant,
    val entity: JsonElement,
    val diff: JsonObject?,
)

@Serializable
data class ChangePageDto(val items: List<ChangeEventDto>, val nextCursor: Long, val hasMore: Boolean)

@Serializable
data class SyncRunDto(
    val startedAt: Instant,
    val finishedAt: Instant?,
    val status: SyncRunStatus,
    val errorCode: String?,
    val itemsFetched: Int,
    val changesDetected: Int,
)

@Serializable
data class StatusCountsDto(val grades: Long, val messages: Long, val unreadMessages: Long)

@Serializable
data class StatusDto(
    val auth: String,
    val lastSync: SyncRunDto?,
    val lastSuccessfulSyncAt: Instant?,
    val nextSyncAt: Instant?,
    val lastEventId: Long,
    val counts: StatusCountsDto,
    val version: String,
)
