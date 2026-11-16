package school.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import school.model.SyncStartedResponseDto
import school.server.sync.AuthState
import school.server.sync.SyncResult
import school.server.sync.SyncService

fun Route.syncRoutes(syncService: SyncService, appScope: Application) {
    post("/sync") {
        val state = syncService.state()
        if (state.authState == AuthState.AUTH_FAILED) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "AUTH_FAILED",
                "E-journal rejected the login. Fix credentials and restart the server.",
            )
        }

        val check = syncService.canTriggerManualSync()
        if (check is SyncResult.Skipped) {
            when (check.reason) {
                "in_progress" -> throw ApiException(
                    HttpStatusCode.Conflict,
                    "SYNC_IN_PROGRESS",
                    "A sync is already in progress.",
                )
                "too_soon" -> {
                    check.retryAfterSeconds?.let {
                        call.response.header(HttpHeaders.RetryAfter, it.toString())
                    }
                    throw ApiException(
                        HttpStatusCode.TooManyRequests,
                        "SYNC_TOO_SOON",
                        "Manual sync is allowed at most once every 5 minutes.",
                    )
                }
            }
        }

        appScope.launch {
            syncService.syncNow(manual = true)
        }
        call.respond(HttpStatusCode.Accepted, SyncStartedResponseDto(status = "started"))
    }
}
