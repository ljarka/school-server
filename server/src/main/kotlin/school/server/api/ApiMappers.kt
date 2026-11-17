package school.server.api

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Instant
import school.model.GradeDto
import school.model.MessageDetailDto
import school.model.MessageSummaryDto
import school.server.store.Store
import school.server.store.StoredGrade
import school.server.store.StoredMessage

internal fun StoredGrade.toDto(): GradeDto = GradeDto(
    id = grade.sourceId,
    childId = childId,
    subject = grade.subject,
    value = grade.value,
    numericValue = grade.numericValue,
    weight = grade.weight,
    category = grade.category,
    semester = grade.semester,
    kind = grade.kind,
    countsToAverage = grade.countsToAverage,
    teacher = grade.teacher,
    comment = grade.comment,
    issuedOn = grade.issuedOn,
    firstSeenAt = firstSeenAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

internal fun StoredMessage.toSummaryDto(): MessageSummaryDto = MessageSummaryDto(
    id = sourceId,
    childId = childId,
    sender = sender,
    subject = subject,
    sentAt = sentAt,
    hasAttachments = hasAttachments,
    read = readAt != null,
    readAt = readAt,
    deletedAt = deletedAt,
)

internal fun StoredMessage.toDetailDto(): MessageDetailDto = MessageDetailDto(
    id = sourceId,
    childId = childId,
    sender = sender,
    subject = subject,
    sentAt = sentAt,
    hasAttachments = hasAttachments,
    read = readAt != null,
    readAt = readAt,
    deletedAt = deletedAt,
    bodyText = bodyText ?: "",
    bodyHtml = bodyHtml ?: "",
    attachments = attachments,
)

internal fun ensureDataAvailable(store: Store) {
    if (store.getLastSuccessfulSyncRun() == null &&
        store.countActiveGrades() == 0L &&
        store.countActiveMessages() == 0L
    ) {
        throw ApiException(
            status = HttpStatusCode.ServiceUnavailable,
            code = "NO_DATA_YET",
            message = "No data has been synced yet.",
        )
    }
}

internal fun parseInstantParam(raw: String?, name: String): Instant? {
    if (raw == null) return null
    return try {
        Instant.parse(raw)
    } catch (e: Exception) {
        throw ApiException(
            HttpStatusCode.BadRequest,
            "BAD_REQUEST",
            "Invalid parameter '$name': expected ISO-8601 instant",
        )
    }
}

internal fun parseBooleanParam(raw: String?, name: String, default: Boolean = false): Boolean {
    if (raw == null) return default
    return when (raw.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw ApiException(
            HttpStatusCode.BadRequest,
            "BAD_REQUEST",
            "Invalid parameter '$name': expected boolean",
        )
    }
}

/**
 * Resolves the optional `?child=` filter (id or login). Absent or blank means "all children";
 * an unknown child is a 404 rather than a silently empty list.
 */
internal fun parseChildParam(store: Store, raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return store.findChildId(raw)
        ?: throw ApiException(HttpStatusCode.NotFound, "CHILD_NOT_FOUND", "Unknown child '${raw.trim()}'")
}

internal fun parseIntParam(raw: String?, name: String, default: Int, min: Int, max: Int): Int {
    if (raw == null) return default
    val parsed = raw.toIntOrNull()
        ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Invalid parameter '$name': expected integer")
    if (parsed !in min..max) {
        throw ApiException(
            HttpStatusCode.BadRequest,
            "BAD_REQUEST",
            "Invalid parameter '$name': must be between $min and $max",
        )
    }
    return parsed
}
