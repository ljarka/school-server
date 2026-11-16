package school.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class MessageAttachmentDto(val name: String = "", val filename: String = "")

@Serializable
internal data class MessageItemDto(
    val messageId: JsonElement? = null,
    @SerialName("Id") val legacyId: JsonElement? = null,
    val senderName: String? = null,
    val senderFirstName: String? = null,
    val senderLastName: String? = null,
    val sender: String? = null,
    val topic: String? = null,
    @SerialName("Subject") val legacySubject: String? = null,
    val sendDate: String? = null,
    @SerialName("SendDate") val legacySendDate: String? = null,
    val content: String? = null,
    @SerialName("Body") val legacyBody: String? = null,
    @SerialName("Message") val encodedMessage: String? = null,
    val hasAttachments: Boolean? = null,
    val attachments: List<MessageAttachmentDto>? = null,
    @SerialName("attachmentsCount") val attachmentsCount: Int? = null,
)

@Serializable
internal data class MessagesListResponseDto(val data: List<MessageItemDto>)

@Serializable
internal data class MessageDetailResponseDto(val data: MessageItemDto)
