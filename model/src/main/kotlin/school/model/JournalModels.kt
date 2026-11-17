package school.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EntityType {
    @SerialName("grade")
    GRADE,

    @SerialName("message")
    MESSAGE,
}

@Serializable
enum class ChangeType {
    @SerialName("created")
    CREATED,

    @SerialName("updated")
    UPDATED,

    @SerialName("deleted")
    DELETED,
}

/** What a message-list item is: an inbox message or a notice-board announcement (ADR 0015). */
@Serializable
enum class MessageType {
    @SerialName("message")
    MESSAGE,

    @SerialName("announcement")
    ANNOUNCEMENT,
}

@Serializable
enum class GradeKind {
    @SerialName("regular")
    REGULAR,

    @SerialName("proposed_semester")
    PROPOSED_SEMESTER,

    @SerialName("semester")
    SEMESTER,

    @SerialName("proposed_final")
    PROPOSED_FINAL,

    @SerialName("final")
    FINAL,
}

@Serializable
enum class SyncRunStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("ok")
    OK,

    @SerialName("error")
    ERROR,

    @SerialName("auth_failed")
    AUTH_FAILED,
}

@Serializable
data class Grade(
    val sourceId: String,
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
)

@Serializable
data class Attachment(val name: String)

@Serializable
data class ChildAccount(val id: String, val login: String, val name: String)

@Serializable
data class MessageHeader(
    val sourceId: String,
    val sender: String,
    val subject: String,
    val sentAt: Instant,
    val hasAttachments: Boolean,
)

@Serializable
data class Message(
    val sourceId: String,
    val sender: String,
    val subject: String,
    val sentAt: Instant,
    val bodyText: String,
    val bodyHtml: String,
    val attachments: List<Attachment>,
    val type: MessageType = MessageType.MESSAGE,
    /** Notice-board visibility window (announcements only). */
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
)
