package school.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import school.model.ChangeEventDto
import school.model.ChangePageDto
import school.model.EntityType
import school.server.store.Store

fun Route.changesRoutes(store: Store) {
    val json = Json { encodeDefaults = true }

    get("/changes") {
        val afterRaw = call.request.queryParameters["after"]
        val sinceRaw = call.request.queryParameters["since"]
        if (afterRaw != null && sinceRaw != null) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "BAD_REQUEST",
                "Parameters 'after' and 'since' cannot be used together",
            )
        }

        val afterId = if (afterRaw != null) {
            val parsed = afterRaw.toLongOrNull()
            if (parsed == null || parsed < 0L) {
                throw ApiException(
                    HttpStatusCode.BadRequest,
                    "BAD_REQUEST",
                    "Invalid parameter 'after': expected integer >= 0",
                )
            }
            parsed
        } else {
            0L
        }

        val childId = store.resolveChildId(call.request.queryParameters["child"])
        val since = parseInstantParam(sinceRaw, "since")
        val type = parseEntityTypeParam(call.request.queryParameters["type"])
        val limit = parseIntParam(call.request.queryParameters["limit"], "limit", default = 100, min = 1, max = 500)

        val rows = if (since != null) {
            store.listChangeEventsSince(since = since, entityType = type, limit = limit + 1, childId = childId)
        } else {
            store.listChangeEventsAfter(afterId = afterId, entityType = type, limit = limit + 1, childId = childId)
        }

        val hasMore = rows.size > limit
        val pageRows = rows.take(limit)

        val items = pageRows.map { event ->
            val entityElement = when (event.entityType) {
                EntityType.GRADE -> {
                    val grade = store.getGrade(event.entityId, event.childId)
                        ?: error("Missing grade ${event.entityId} for change event ${event.id}")
                    json.encodeToJsonElement(grade.toDto())
                }
                EntityType.MESSAGE -> {
                    val message = store.getMessage(event.entityId, event.childId)
                        ?: error("Missing message ${event.entityId} for change event ${event.id}")
                    json.encodeToJsonElement(message.toSummaryDto())
                }
            }
            val diffObj = event.diffJson?.let { json.decodeFromString<JsonObject>(it) }

            ChangeEventDto(
                id = event.id,
                childId = event.childId,
                entityType = event.entityType,
                entityId = event.entityId,
                changeType = event.changeType,
                detectedAt = event.detectedAt,
                entity = entityElement,
                diff = diffObj,
            )
        }

        val nextCursor = items.lastOrNull()?.id ?: afterId
        call.respond(
            ChangePageDto(
                items = items,
                nextCursor = nextCursor,
                hasMore = hasMore,
            ),
        )
    }
}

private fun parseEntityTypeParam(raw: String?): EntityType? {
    if (raw == null) return null
    return when (raw) {
        "grade" -> EntityType.GRADE
        "message" -> EntityType.MESSAGE
        else -> throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Invalid parameter 'type'")
    }
}
