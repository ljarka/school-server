package school.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import school.model.SyncStartedResponseDto
import school.server.sync.SyncResult
import school.server.sync.SyncService

fun Route.syncRoutes(syncService: SyncService, appScope: Application) {
    post("/sync") {
        when (val skipped = syncService.triggerManualSync(appScope)) {
            null -> call.respond(HttpStatusCode.Accepted, SyncStartedResponseDto(status = "started"))
            SyncResult.AuthFailed -> throw ApiException(
                HttpStatusCode.Conflict,
                "AUTH_FAILED",
                "E-journal rejected the login. Fix credentials and restart the server.",
            )
            is SyncResult.Skipped -> if (skipped.reason == "too_soon") {
                skipped.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
                throw ApiException(
                    HttpStatusCode.TooManyRequests,
                    "SYNC_TOO_SOON",
                    "Manual sync is allowed at most once every 5 minutes.",
                )
            } else {
                throw ApiException(HttpStatusCode.Conflict, "SYNC_IN_PROGRESS", "A sync is already in progress.")
            }
            else -> error("Unexpected manual sync result: $skipped")
        }
    }
}
