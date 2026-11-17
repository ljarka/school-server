package school.server.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import school.model.AuthRejected
import school.model.ChildAccount
import school.model.Grade
import school.model.JournalClient
import school.model.JournalUnavailable
import school.model.Message
import school.model.MessageHeader
import school.model.MessageType
import school.model.SyncRunStatus
import school.server.store.Store
import school.server.store.StoredMessage

data class SyncConfig(
    val intervalMinutes: Int = 15,
    val initialDelay: Duration = 5.seconds,
    val manualCooldown: Duration = 5.minutes,
)

enum class AuthState {
    UNKNOWN,
    OK,
    AUTH_FAILED,
}

data class SyncState(val authState: AuthState, val nextSyncAt: Instant?, val consecutiveErrors: Int)

sealed interface SyncResult {
    data class Ok(val changes: Int) : SyncResult

    data class Skipped(val reason: String, val retryAfterSeconds: Long? = null) : SyncResult

    data class Failed(val code: String) : SyncResult

    data object AuthFailed : SyncResult
}

class SyncService(
    private val journal: JournalClient,
    private val store: Store,
    private val detector: ChangeDetector = ChangeDetector(),
    private val clock: Clock = Clock.System,
    private val config: SyncConfig = SyncConfig(),
    private val random: Random = Random.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(SyncService::class.java)
    private val mutex = Mutex()
    private val manualLock = Any()

    @Volatile
    private var authState: AuthState = AuthState.UNKNOWN

    @Volatile
    private var sessionLoggedIn: Boolean = false

    @Volatile
    private var consecutiveErrors: Int = 0

    @Volatile
    private var nextSyncAt: Instant? = null

    @Volatile
    private var lastManualTriggerAt: Instant? = null

    private var loopJob: Job? = null

    fun start(scope: CoroutineScope): Job {
        val job = scope.launch {
            val initialDelay = config.initialDelay
            nextSyncAt = clock.now() + initialDelay
            delay(initialDelay)

            while (isActive && authState != AuthState.AUTH_FAILED) {
                syncNow()
                if (authState == AuthState.AUTH_FAILED) {
                    nextSyncAt = null
                    break
                }
                val waitDuration = nextDelayDuration()
                nextSyncAt = clock.now() + waitDuration
                delay(waitDuration)
            }
        }
        loopJob = job
        return job
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun state(): SyncState = SyncState(
        authState = authState,
        nextSyncAt = nextSyncAt,
        consecutiveErrors = consecutiveErrors,
    )

    /**
     * Starts a manual sync in [scope] unless it is not allowed right now. The cooldown check, the lock and
     * the cooldown timestamp are taken together, so two concurrent requests cannot both start a sync.
     *
     * @return `null` when the sync was started, otherwise why it was not.
     */
    fun triggerManualSync(scope: CoroutineScope): SyncResult? {
        if (authState == AuthState.AUTH_FAILED) {
            return SyncResult.AuthFailed
        }
        synchronized(manualLock) {
            val now = clock.now()
            val prevManual = lastManualTriggerAt
            if (prevManual != null) {
                val elapsed = now - prevManual
                if (elapsed < config.manualCooldown) {
                    val remaining = maxOf(1L, (config.manualCooldown - elapsed).inWholeSeconds)
                    return SyncResult.Skipped(reason = "too_soon", retryAfterSeconds = remaining)
                }
            }
            if (!mutex.tryLock()) {
                return SyncResult.Skipped(reason = "in_progress")
            }
            lastManualTriggerAt = now
            // invokeOnCompletion also fires when the job is cancelled before it starts,
            // so the lock taken above is always released exactly once.
            scope.launch { executeSyncRun(now) }.invokeOnCompletion { mutex.unlock() }
            return null
        }
    }

    /** Runs one sync now unless another one is in progress (used by the scheduler loop). */
    suspend fun syncNow(): SyncResult {
        if (authState == AuthState.AUTH_FAILED) {
            return SyncResult.AuthFailed
        }
        if (!mutex.tryLock()) {
            return SyncResult.Skipped(reason = "in_progress")
        }
        return try {
            executeSyncRun(clock.now())
        } finally {
            mutex.unlock()
        }
    }

    internal fun nextDelayDuration(): Duration {
        if (consecutiveErrors > 0) {
            val shift = minOf(6, consecutiveErrors - 1)
            val backoffMinutes = minOf(60, 1 shl shift)
            return backoffMinutes.minutes
        }
        val jitterSeconds = random.nextInt(0, 61)
        return config.intervalMinutes.minutes + jitterSeconds.seconds
    }

    private suspend fun executeSyncRun(startedAt: Instant): SyncResult = withContext(ioDispatcher) {
        val runId = store.insertSyncRun(startedAt)
        try {
            storeOutcomes(runId, fetchAll())
        } catch (e: CancellationException) {
            runCatching { finishFailedRun(runId, "cancelled") }
            throw e
        } catch (e: AuthRejected) {
            authState = AuthState.AUTH_FAILED
            sessionLoggedIn = false
            store.setMeta("auth_state", "auth_failed")
            store.finishSyncRun(
                id = runId,
                finishedAt = clock.now(),
                status = SyncRunStatus.AUTH_FAILED,
                errorCode = e.message ?: "auth_failed",
                itemsFetched = 0,
                changesDetected = 0,
            )
            logger.error("E-journal rejected the login. Fix SCHOOL_LOGIN/SCHOOL_PASSWORD and restart.")
            SyncResult.AuthFailed
        } catch (e: JournalUnavailable) {
            val code = e.message ?: "journal_unavailable"
            consecutiveErrors += 1
            finishFailedRun(runId, code)
            logger.warn("Sync failed: {}", code)
            SyncResult.Failed(code)
        } catch (e: Exception) {
            val code = "unexpected_error"
            consecutiveErrors += 1
            finishFailedRun(runId, code)
            logger.error("Unexpected sync error", e)
            SyncResult.Failed(code)
        }
    }

    private fun finishFailedRun(runId: Long, code: String) {
        store.finishSyncRun(
            id = runId,
            finishedAt = clock.now(),
            status = SyncRunStatus.ERROR,
            errorCode = code,
            itemsFetched = 0,
            changesDetected = 0,
        )
    }

    private fun fetchAll(): List<ChildFetchOutcome> {
        if (!sessionLoggedIn) {
            journal.login()
            sessionLoggedIn = true
        }

        val discovered = journal.listChildren()
        if (discovered.isNotEmpty()) {
            store.upsertChildAccounts(discovered, clock.now())
        }
        val targets = discovered.ifEmpty {
            listOf(ChildAccount(id = DEFAULT_CHILD_ID, login = DEFAULT_CHILD_ID, name = DEFAULT_CHILD_ID))
        }
        return targets.map { child -> fetchChild(child.id) }
    }

    private fun fetchChild(childId: String): ChildFetchOutcome {
        var grades: List<Grade>? = null
        var gradesError: String? = null
        try {
            // null = the e-journal denies the grades resource for this account: keep what we have.
            grades = journal.fetchGrades(childId)
        } catch (e: JournalUnavailable) {
            gradesError = e.message ?: "journal_unavailable"
        }

        var messages: List<Message>? = null
        var messagesComplete = true
        var messagesError: String? = null
        try {
            val listing = journal.fetchMessageHeaders(childId)
            messagesComplete = listing.complete
            val existingById = store.listMessages(
                includeDeleted = true,
                limit = Int.MAX_VALUE,
                childId = childId,
                type = MessageType.MESSAGE,
            ).associateBy { it.sourceId }

            messages = listing.headers.map { header ->
                val existing = existingById[header.sourceId]
                if (existing != null && headerMatches(existing, header)) {
                    existing.toMessage()
                } else {
                    journal.fetchMessage(header.sourceId, childId)
                }
            }
        } catch (e: JournalUnavailable) {
            messagesError = e.message ?: "journal_unavailable"
        }

        var announcements: List<Message>? = null
        var announcementsError: String? = null
        try {
            // null = the board is not available for this account: keep what we have (ADR 0014, 0015).
            announcements = journal.fetchAnnouncements(childId)
        } catch (e: JournalUnavailable) {
            announcementsError = e.message ?: "journal_unavailable"
        }

        return ChildFetchOutcome(
            childId = childId,
            grades = grades,
            gradesError = gradesError,
            messages = messages,
            messagesComplete = messagesComplete,
            messagesError = messagesError,
            announcements = announcements,
            announcementsError = announcementsError,
        )
    }

    private fun storeOutcomes(runId: Long, outcomes: List<ChildFetchOutcome>): SyncResult {
        val finishedAt = clock.now()
        var changesCount = 0
        var itemsFetched = 0
        var firstError: String? = null

        for (outcome in outcomes) {
            val gradeChanges = outcome.grades?.let { fresh ->
                detector.diffGrades(
                    stored = store.listGrades(includeDeleted = true, childId = outcome.childId),
                    fresh = fresh,
                    complete = true,
                )
            } ?: Changes()

            // Inbox and board are diffed separately, so a failed or cut listing of one never deletes the other.
            val inboxChanges = outcome.messages?.let { fresh ->
                detector.diffMessages(
                    stored = storedMessages(outcome.childId, MessageType.MESSAGE),
                    fresh = fresh,
                    complete = outcome.messagesComplete,
                )
            } ?: Changes()
            val announcementChanges = outcome.announcements?.let { fresh ->
                detector.diffMessages(
                    stored = storedMessages(outcome.childId, MessageType.ANNOUNCEMENT),
                    fresh = fresh,
                    complete = true,
                )
            } ?: Changes()
            val messageChanges = inboxChanges + announcementChanges

            changesCount += store.applyChanges(
                syncRunId = runId,
                gradeChanges = gradeChanges,
                messageChanges = messageChanges,
                now = finishedAt,
                childId = outcome.childId,
            )
            itemsFetched += (outcome.grades?.size ?: 0) + (outcome.messages?.size ?: 0) +
                (outcome.announcements?.size ?: 0)
            if (firstError == null) {
                firstError = outcome.gradesError ?: outcome.messagesError ?: outcome.announcementsError
            }
        }

        if (firstError != null) {
            consecutiveErrors += 1
            store.finishSyncRun(
                id = runId,
                finishedAt = finishedAt,
                status = SyncRunStatus.ERROR,
                errorCode = firstError,
                itemsFetched = itemsFetched,
                changesDetected = changesCount,
            )
            logger.warn("Sync completed with error: {}", firstError)
            return SyncResult.Failed(firstError)
        }

        consecutiveErrors = 0
        authState = AuthState.OK
        store.setMeta("auth_state", "ok")
        store.finishSyncRun(
            id = runId,
            finishedAt = finishedAt,
            status = SyncRunStatus.OK,
            errorCode = null,
            itemsFetched = itemsFetched,
            changesDetected = changesCount,
        )
        logger.info("Sync completed: {} items fetched, {} changes", itemsFetched, changesCount)
        return SyncResult.Ok(changesCount)
    }

    private fun storedMessages(childId: String, type: MessageType): List<StoredMessage> =
        store.listMessages(includeDeleted = true, limit = Int.MAX_VALUE, childId = childId, type = type)

    private fun StoredMessage.toMessage(): Message = Message(
        sourceId = sourceId,
        sender = sender,
        subject = subject,
        sentAt = sentAt,
        bodyText = bodyText!!,
        bodyHtml = bodyHtml ?: bodyText,
        attachments = attachments,
        type = type,
        validFrom = validFrom,
        validTo = validTo,
    )

    private fun headerMatches(existing: StoredMessage, header: MessageHeader): Boolean = existing.deletedAt == null &&
        existing.bodyText != null &&
        existing.sender == header.sender &&
        existing.subject == header.subject &&
        existing.sentAt == header.sentAt &&
        existing.hasAttachments == header.hasAttachments

    private data class ChildFetchOutcome(
        val childId: String,
        val grades: List<Grade>?,
        val gradesError: String?,
        val messages: List<Message>?,
        val messagesComplete: Boolean,
        val messagesError: String?,
        val announcements: List<Message>?,
        val announcementsError: String?,
    )

    private companion object {
        const val DEFAULT_CHILD_ID = "default"
    }
}
