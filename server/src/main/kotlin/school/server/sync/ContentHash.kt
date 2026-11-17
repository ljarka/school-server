package school.server.sync

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import school.model.Grade
import school.model.Message

object ContentHash {
    private val json = Json { encodeDefaults = true }

    fun gradeHash(grade: Grade): String {
        val canonical = buildJsonObject {
            put("category", JsonPrimitive(grade.category))
            put("comment", JsonPrimitive(grade.comment))
            put("countsToAverage", JsonPrimitive(grade.countsToAverage))
            put("issuedOn", JsonPrimitive(grade.issuedOn.toString()))
            put("kind", JsonPrimitive(grade.kind.name))
            put("numericValue", JsonPrimitive(grade.numericValue))
            put("semester", JsonPrimitive(grade.semester))
            put("subject", JsonPrimitive(grade.subject))
            put("teacher", JsonPrimitive(grade.teacher))
            put("value", JsonPrimitive(grade.value))
            put("weight", JsonPrimitive(grade.weight))
        }
        return sha256(json.encodeToString(JsonObject.serializer(), canonical))
    }

    fun messageHash(message: Message): String {
        val canonical = buildJsonObject {
            put(
                "attachments",
                buildJsonArray {
                    for (att in message.attachments) {
                        add(buildJsonObject { put("name", JsonPrimitive(att.name)) })
                    }
                },
            )
            put("bodyText", JsonPrimitive(message.bodyText))
            put("sender", JsonPrimitive(message.sender))
            put("sentAt", JsonPrimitive(message.sentAt.toString()))
            put("subject", JsonPrimitive(message.subject))
            // Only announcements carry these; leaving them out otherwise keeps message hashes stable.
            message.validFrom?.let { put("validFrom", JsonPrimitive(it.toString())) }
            message.validTo?.let { put("validTo", JsonPrimitive(it.toString())) }
        }
        return sha256(json.encodeToString(JsonObject.serializer(), canonical))
    }

    internal fun encodeDiff(diff: Map<String, Pair<JsonPrimitive, JsonPrimitive>>): String {
        val obj = buildJsonObject {
            for ((field, pair) in diff.toSortedMap()) {
                put(field, JsonArray(listOf(pair.first, pair.second)))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
