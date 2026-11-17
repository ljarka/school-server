package school.journal

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Entities
import school.model.JournalUnavailable
import school.model.Message
import school.model.MessageType

/** Maps notice-board entries to [Message]s with [MessageType.ANNOUNCEMENT] (ADR 0015). */
internal object AnnouncementsMapper {
    private const val LAYOUT_CHANGED = "layout_changed:announcements"
    private val warsawZone = TimeZone.of("Europe/Warsaw")
    private val htmlTag = Regex("<[a-zA-Z/!][^>]*>")

    fun map(notices: List<SchoolNoticeDto>, users: Map<Long, UserDto>): List<Message> = notices.map {
        mapOne(it, users)
    }

    fun mapOne(dto: SchoolNoticeDto, users: Map<Long, UserDto>): Message {
        val rawId = (dto.id as? JsonPrimitive)?.content?.trim()?.ifEmpty { null }
            ?: throw JournalUnavailable(LAYOUT_CHANGED)
        val validFrom = parseDate(dto.startDate)
        val validTo = parseDate(dto.endDate)
        val sentAt = dto.creationDate?.trim()?.ifEmpty { null }?.let { raw ->
            try {
                MessagesMapper.parseSentAt(raw)
            } catch (e: JournalUnavailable) {
                throw JournalUnavailable(LAYOUT_CHANGED, e)
            }
        } ?: validFrom?.atStartOfDayIn(warsawZone)
            ?: throw JournalUnavailable(LAYOUT_CHANGED)

        val bodyHtml = MessagesMapper.sanitiseHtml(toHtml(dto.content.orEmpty()))

        return Message(
            sourceId = "a_$rawId",
            sender = dto.addedBy?.let { users[it.id] }?.let(::fullName).orEmpty(),
            subject = dto.subject?.trim().orEmpty(),
            sentAt = sentAt,
            bodyText = MessagesMapper.htmlToText(bodyHtml),
            bodyHtml = bodyHtml,
            attachments = emptyList(),
            type = MessageType.ANNOUNCEMENT,
            validFrom = validFrom,
            validTo = validTo,
        )
    }

    /** Content is plain text with line breaks or HTML; plain text is escaped and its breaks kept. */
    fun toHtml(content: String): String = if (htmlTag.containsMatchIn(content)) {
        content
    } else {
        content.replace("\r\n", "\n").split('\n').joinToString("<br>") { Entities.escape(it) }
    }

    private fun parseDate(raw: String?): LocalDate? {
        val trimmed = raw?.trim()?.ifEmpty { null } ?: return null
        return try {
            LocalDate.parse(trimmed.take(DATE_LENGTH))
        } catch (e: Exception) {
            throw JournalUnavailable(LAYOUT_CHANGED, e)
        }
    }

    private fun fullName(user: UserDto): String = listOfNotNull(user.firstName?.trim(), user.lastName?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    private const val DATE_LENGTH = 10
}
