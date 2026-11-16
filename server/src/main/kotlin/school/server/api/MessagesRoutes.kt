package school.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.util.Base64
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import school.model.MarkAllReadResponseDto
import school.model.MessagesPageDto
import school.server.store.Store

fun Route.messagesRoutes(store: Store, clock: Clock = Clock.System) {
    get("/messages") {
        ensureDataAvailable(store)

        val unreadOnly = parseBooleanParam(call.request.queryParameters["unread"], "unread")
        val since = parseInstantParam(call.request.queryParameters["since"], "since")
        val limit = parseIntParam(call.request.queryParameters["limit"], "limit", default = 50, min = 1, max = 100)
        val cursorRaw = call.request.queryParameters["cursor"]
        val cursor = cursorRaw?.let { decodeCursor(it) }
        val includeDeleted = parseBooleanParam(call.request.queryParameters["includeDeleted"], "includeDeleted")

        val all = store.listMessages(
            unreadOnly = unreadOnly,
            includeDeleted = includeDeleted,
            limit = 10_000,
            offset = 0,
        ).filter { msg ->
            (since == null || msg.sentAt >= since) &&
                (cursor == null || isAfterCursor(msg.sentAt, msg.sourceId, cursor))
        }

        val page = all.take(limit)
        val hasMore = all.size > limit
        val nextCursor = if (hasMore && page.isNotEmpty()) {
            encodeCursor(page.last().sentAt, page.last().sourceId)
        } else {
            null
        }

        call.respond(
            MessagesPageDto(
                items = page.map { it.toSummaryDto() },
                nextCursor = nextCursor,
            ),
        )
    }

    get("/messages/{id}") {
        val id = call.parameters["id"]
            ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Missing parameter 'id'")
        val msg = store.getMessage(id)
            ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Message not found")
        call.respond(msg.toDetailDto())
    }

    put("/messages/{id}/read") {
        val id = call.parameters["id"]
            ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Missing parameter 'id'")
        val existing = store.getMessage(id)
            ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Message not found")
        if (existing.readAt == null) {
            store.markMessageRead(id, clock.now())
        }
        val updated = store.getMessage(id)!!
        call.respond(updated.toDetailDto())
    }

    delete("/messages/{id}/read") {
        val id = call.parameters["id"]
            ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Missing parameter 'id'")
        store.getMessage(id)
            ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Message not found")
        store.markMessageUnread(id)
        val updated = store.getMessage(id)!!
        call.respond(updated.toDetailDto())
    }

    post("/messages/read-all") {
        val marked = store.markAllMessagesRead(clock.now())
        call.respond(MarkAllReadResponseDto(marked = marked))
    }
}

private data class MessageCursor(val sentAt: Instant, val sourceId: String)

private fun encodeCursor(sentAt: Instant, sourceId: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$sentAt|$sourceId".toByteArray(Charsets.UTF_8))

private fun decodeCursor(raw: String): MessageCursor = try {
    val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
    val parts = decoded.split('|', limit = 2)
    if (parts.size != 2) error("Invalid cursor")
    MessageCursor(sentAt = Instant.parse(parts[0]), sourceId = parts[1])
} catch (e: Exception) {
    throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Invalid parameter 'cursor'")
}

private fun isAfterCursor(sentAt: Instant, sourceId: String, cursor: MessageCursor): Boolean =
    sentAt < cursor.sentAt || (sentAt == cursor.sentAt && sourceId < cursor.sourceId)
