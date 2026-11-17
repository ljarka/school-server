package school.server.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import school.model.StatusCountsDto
import school.model.StatusDto
import school.model.SyncRunDto
import school.server.store.Store
import school.server.sync.AuthState
import school.server.sync.SyncService

/** Version from the server jar manifest (set from the release tag), or "dev" for local runs. */
val appVersion: String = Store::class.java.`package`?.implementationVersion ?: "dev"

fun Route.statusRoutes(store: Store, syncService: SyncService?, version: String = appVersion) {
    get("/status") {
        val syncState = syncService?.state()
        val auth = when (syncState?.authState) {
            AuthState.OK -> "ok"
            AuthState.AUTH_FAILED -> "auth_failed"
            AuthState.UNKNOWN, null -> store.getMeta("auth_state") ?: "unknown"
        }

        val lastSync = store.getLastSyncRun()?.let { run ->
            SyncRunDto(
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                status = run.status,
                errorCode = run.errorCode,
                itemsFetched = run.itemsFetched,
                changesDetected = run.changesDetected,
            )
        }
        val lastSuccess = store.getLastSuccessfulSyncRun()

        call.respond(
            StatusDto(
                auth = auth,
                lastSync = lastSync,
                lastSuccessfulSyncAt = lastSuccess?.finishedAt,
                nextSyncAt = syncState?.nextSyncAt,
                lastEventId = store.lastChangeEventId(),
                counts = StatusCountsDto(
                    grades = store.countActiveGrades(),
                    messages = store.countActiveMessages(),
                    unreadMessages = store.countUnreadMessages(),
                ),
                version = version,
            ),
        )
    }
}
