package school.journal

import java.util.Base64
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import school.model.Attachment
import school.model.JournalUnavailable
import school.model.Message
import school.model.MessageHeader

internal object MessagesMapper {
    private val warsawZone = TimeZone.of("Europe/Warsaw")
    private val messageSafelist =
        Safelist.basic()
            .addTags("br")
            .addEnforcedAttribute("a", "rel", "noopener")

    fun mapHeader(dto: MessageItemDto): MessageHeader {
        val rawId = extractId(dto)
        val sender = extractSender(dto)
        val subject = (dto.topic ?: dto.legacySubject)?.trim()
            ?: throw JournalUnavailable("layout_changed:messages")
        val sentAt = parseSentAt(dto.sendDate ?: dto.legacySendDate)
        val hasAttachments = dto.hasAttachments
            ?: ((dto.attachmentsCount ?: 0) > 0) ||
            (!dto.attachments.isNullOrEmpty())

        return MessageHeader(
            sourceId = "m_$rawId",
            sender = sender,
            subject = subject,
            sentAt = sentAt,
            hasAttachments = hasAttachments,
        )
    }

    fun mapDetail(dto: MessageItemDto): Message {
        val header = mapHeader(dto)
        val rawBody = extractBody(dto)
        val bodyHtml = sanitiseHtml(rawBody)
        val bodyText = htmlToText(bodyHtml)
        val attachments = dto.attachments.orEmpty()
            .mapNotNull { att ->
                val name = att.name.ifBlank { att.filename }.trim()
                if (name.isEmpty()) null else Attachment(name = name)
            }

        return Message(
            sourceId = header.sourceId,
            sender = header.sender,
            subject = header.subject,
            sentAt = header.sentAt,
            bodyText = bodyText,
            bodyHtml = bodyHtml,
            attachments = attachments,
        )
    }

    fun sanitiseHtml(rawHtml: String): String = Jsoup.clean(
        rawHtml,
        "",
        messageSafelist,
        Document.OutputSettings().prettyPrint(false),
    )

    fun htmlToText(sanitisedHtml: String): String {
        val doc = Jsoup.parseBodyFragment(sanitisedHtml)
        doc.outputSettings().prettyPrint(false)
        doc.select("br").append("\\n")
        doc.select("p, div, li, tr").prepend("\\n")
        val rawText = doc.text().replace("\\n", "\n")
        return rawText
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun parseSentAt(raw: String?): Instant {
        val trimmed = raw?.trim()?.ifEmpty { null }
            ?: throw JournalUnavailable("layout_changed:messages")
        runCatching { return Instant.parse(trimmed) }
        val normalized = trimmed.replace(' ', 'T')
        return try {
            LocalDateTime.parse(normalized).toInstant(warsawZone)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:messages", e)
        }
    }

    private fun extractId(dto: MessageItemDto): String {
        val elem = dto.messageId ?: dto.legacyId
            ?: throw JournalUnavailable("layout_changed:messages")
        val primitive = elem as? JsonPrimitive
            ?: throw JournalUnavailable("layout_changed:messages")
        return primitive.content.trim().ifEmpty {
            throw JournalUnavailable("layout_changed:messages")
        }
    }

    private fun extractSender(dto: MessageItemDto): String {
        val fullName = listOfNotNull(
            dto.senderFirstName?.trim()?.ifEmpty { null },
            dto.senderLastName?.trim()?.ifEmpty { null },
        ).joinToString(" ")
        return dto.senderName?.trim()?.ifEmpty { null }
            ?: fullName.ifEmpty { null }
            ?: dto.sender?.trim()?.ifEmpty { null }
            ?: "?"
    }

    private fun extractBody(dto: MessageItemDto): String {
        val encoded = dto.encodedMessage
        if (!encoded.isNullOrBlank()) {
            val decoded = runCatching {
                String(Base64.getDecoder().decode(encoded.replace("\\s".toRegex(), "")), Charsets.UTF_8)
            }.getOrNull()
            if (decoded != null) {
                val match = CDATA_CONTENT_REGEX.find(decoded)
                return match?.groupValues?.get(1) ?: decoded
            }
        }
        return dto.content ?: dto.legacyBody ?: ""
    }

    private val CDATA_CONTENT_REGEX = Regex("<Content><!\\[CDATA\\[([\\s\\S]*?)]]></Content>")
}
