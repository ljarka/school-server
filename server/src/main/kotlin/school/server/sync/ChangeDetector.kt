package school.server.sync

import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import school.model.Grade
import school.model.Message
import school.server.store.StoredGrade
import school.server.store.StoredMessage

data class CreatedItem<T>(val item: T, val contentHash: String)

data class UpdatedItem<T>(val item: T, val contentHash: String, val firstSeenAt: Instant, val diffJson: String)

data class Changes<T>(
    val created: List<CreatedItem<T>> = emptyList(),
    val updated: List<UpdatedItem<T>> = emptyList(),
    val deletedIds: List<String> = emptyList(),
) {
    val totalCount: Int get() = created.size + updated.size + deletedIds.size
}

class ChangeDetector {
    fun diffGrades(stored: List<StoredGrade>, fresh: List<Grade>, complete: Boolean): Changes<Grade> {
        val sortedFresh = fresh.sortedWith(compareBy<Grade> { it.issuedOn }.thenBy { it.sourceId })
        val storedById = stored.associateBy { it.grade.sourceId }

        val created = mutableListOf<CreatedItem<Grade>>()
        val updated = mutableListOf<UpdatedItem<Grade>>()

        for (grade in sortedFresh) {
            val hash = ContentHash.gradeHash(grade)
            val prev = storedById[grade.sourceId]
            when {
                prev == null || prev.deletedAt != null -> {
                    created += CreatedItem(grade, hash)
                }
                prev.contentHash != hash -> {
                    val diff = gradeFieldDiff(prev.grade, grade)
                    updated += UpdatedItem(
                        item = grade,
                        contentHash = hash,
                        firstSeenAt = prev.firstSeenAt,
                        diffJson = ContentHash.encodeDiff(diff),
                    )
                }
            }
        }

        val deletedIds = if (complete) {
            val freshIds = sortedFresh.mapTo(HashSet()) { it.sourceId }
            stored
                .filter { it.deletedAt == null && it.grade.sourceId !in freshIds }
                .map { it.grade.sourceId }
                .sorted()
        } else {
            emptyList()
        }

        return Changes(created = created, updated = updated, deletedIds = deletedIds)
    }

    fun diffMessages(stored: List<StoredMessage>, fresh: List<Message>, complete: Boolean): Changes<Message> {
        val sortedFresh = fresh.sortedWith(compareBy<Message> { it.sentAt }.thenBy { it.sourceId })
        val storedById = stored.associateBy { it.sourceId }

        val created = mutableListOf<CreatedItem<Message>>()
        val updated = mutableListOf<UpdatedItem<Message>>()

        for (message in sortedFresh) {
            val hash = ContentHash.messageHash(message)
            val prev = storedById[message.sourceId]
            when {
                prev == null || prev.deletedAt != null -> {
                    created += CreatedItem(message, hash)
                }
                prev.contentHash != hash -> {
                    val diff = messageFieldDiff(prev, message)
                    updated += UpdatedItem(
                        item = message,
                        contentHash = hash,
                        firstSeenAt = prev.firstSeenAt,
                        diffJson = ContentHash.encodeDiff(diff),
                    )
                }
            }
        }

        val deletedIds = if (complete) {
            val freshIds = sortedFresh.mapTo(HashSet()) { it.sourceId }
            stored
                .filter { it.deletedAt == null && it.sourceId !in freshIds }
                .map { it.sourceId }
                .sorted()
        } else {
            emptyList()
        }

        return Changes(created = created, updated = updated, deletedIds = deletedIds)
    }

    private fun gradeFieldDiff(old: Grade, new: Grade): Map<String, Pair<JsonPrimitive, JsonPrimitive>> {
        val diff = mutableMapOf<String, Pair<JsonPrimitive, JsonPrimitive>>()
        if (old.subject != new.subject) diff["subject"] = JsonPrimitive(old.subject) to JsonPrimitive(new.subject)
        if (old.value != new.value) diff["value"] = JsonPrimitive(old.value) to JsonPrimitive(new.value)
        if (old.numericValue != new.numericValue) {
            diff["numericValue"] = JsonPrimitive(old.numericValue) to JsonPrimitive(new.numericValue)
        }
        if (old.weight != new.weight) diff["weight"] = JsonPrimitive(old.weight) to JsonPrimitive(new.weight)
        if (old.category != new.category) diff["category"] = JsonPrimitive(old.category) to JsonPrimitive(new.category)
        if (old.semester != new.semester) diff["semester"] = JsonPrimitive(old.semester) to JsonPrimitive(new.semester)
        if (old.kind != new.kind) diff["kind"] = JsonPrimitive(old.kind.name) to JsonPrimitive(new.kind.name)
        if (old.countsToAverage != new.countsToAverage) {
            diff["countsToAverage"] = JsonPrimitive(old.countsToAverage) to JsonPrimitive(new.countsToAverage)
        }
        if (old.teacher != new.teacher) diff["teacher"] = JsonPrimitive(old.teacher) to JsonPrimitive(new.teacher)
        if (old.comment != new.comment) diff["comment"] = JsonPrimitive(old.comment) to JsonPrimitive(new.comment)
        if (old.issuedOn != new.issuedOn) {
            diff["issuedOn"] = JsonPrimitive(old.issuedOn.toString()) to JsonPrimitive(new.issuedOn.toString())
        }
        return diff
    }

    private fun messageFieldDiff(old: StoredMessage, new: Message): Map<String, Pair<JsonPrimitive, JsonPrimitive>> {
        val diff = mutableMapOf<String, Pair<JsonPrimitive, JsonPrimitive>>()
        if (old.sender != new.sender) diff["sender"] = JsonPrimitive(old.sender) to JsonPrimitive(new.sender)
        if (old.subject != new.subject) diff["subject"] = JsonPrimitive(old.subject) to JsonPrimitive(new.subject)
        if (old.sentAt != new.sentAt) {
            diff["sentAt"] = JsonPrimitive(old.sentAt.toString()) to JsonPrimitive(new.sentAt.toString())
        }
        if (old.bodyText != new.bodyText) {
            diff["bodyText"] = JsonPrimitive(old.bodyText) to JsonPrimitive(new.bodyText)
        }
        if (old.attachments != new.attachments) {
            diff["attachments"] =
                JsonPrimitive(old.attachments.joinToString(",") { it.name }) to
                JsonPrimitive(new.attachments.joinToString(",") { it.name })
        }
        return diff
    }
}
