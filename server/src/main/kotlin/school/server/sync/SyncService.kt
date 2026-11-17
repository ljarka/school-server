package school.server.sync

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

    fun canTriggerManualSync(): SyncResult.Skipped? {
        val now = clock.now()
        val prevManual = lastManualTriggerAt
        if (prevManual != null) {
            val elapsed = now - prevManual
            if (elapsed < config.manualCooldown) {
                val remaining = maxOf(1L, (config.manualCooldown - elapsed).inWholeSeconds)
                return SyncResult.Skipped(reason = "too_soon", retryAfterSeconds = remaining)
            }
        }
        if (mutex.isLocked) {
            return SyncResult.Skipped(reason = "in_progress")
        }
        return null
    }

    suspend fun syncNow(manual: Boolean = false): SyncResult {
        if (authState == AuthState.AUTH_FAILED) {
            return SyncResult.AuthFailed
        }

        val now = clock.now()
        if (manual) {
            val prevManual = lastManualTriggerAt
            if (prevManual != null) {
                val elapsed = now - prevManual
                if (elapsed < config.manualCooldown) {
                    val remaining = maxOf(1L, (config.manualCooldown - elapsed).inWholeSeconds)
                    return SyncResult.Skipped(reason = "too_soon", retryAfterSeconds = remaining)
                }
            }
        }

        if (!mutex.tryLock()) {
            return SyncResult.Skipped(reason = "in_progress")
        }

        return try {
            if (manual) {
                lastManualTriggerAt = now
            }
            executeSyncRun(now)
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

    private suspend fun executeSyncRun(startedAt: Instant): SyncResult {
        val runId = store.insertSyncRun(startedAt)

        return try {
            val outcomes = withContext(ioDispatcher) {
                if (!sessionLoggedIn) {
                    journal.login()
                    sessionLoggedIn = true
                }

                val discovered = journal.listChildren()
                if (discovered.isNotEmpty()) {
                    store.upsertChildAccounts(discovered, clock.now())
                }
                val targets = discovered.ifEmpty {
                    listOf(ChildAccount(id = "default", login = "default", name = "default"))
                }

                targets.map { child ->
                    var grades: List<Grade>? = null
                    var gradesError: String? = null
                    try {
                        grades = journal.fetchGrades(child.id)
                    } catch (e: AuthRejected) {
                        throw e
                    } catch (e: JournalUnavailable) {
                        gradesError = e.message ?: "journal_unavailable"
                    }

                    var messages: List<Message>? = null
                    var messagesError: String? = null
                    try {
                        val headers = journal.fetchMessageHeaders(child.id)
                        val existingById = store.listMessages(
                            includeDeleted = true,
                            limit = 10_000,
                            childId = child.id,
                        ).associateBy { it.sourceId }

                        messages = headers.map { header ->
                            val existing = existingById[header.sourceId]
                            if (existing != null && headerMatches(existing, header)) {
                                Message(
                                    sourceId = existing.sourceId,
                                    sender = existing.sender,
                                    subject = existing.subject,
                                    sentAt = existing.sentAt,
                                    bodyText = existing.bodyText!!,
                                    bodyHtml = existing.bodyHtml ?: existing.bodyText,
                                    attachments = existing.attachments,
                                )
                            } else {
                                journal.fetchMessage(header.sourceId, child.id)
                            }
                        }
                    } catch (e: AuthRejected) {
                        throw e
                    } catch (e: JournalUnavailable) {
                        messagesError = e.message ?: "journal_unavailable"
                    }

                    ChildFetchOutcome(
                        childId = child.id,
                        grades = grades,
                        gradesError = gradesError,
                        messages = messages,
                        messagesError = messagesError,
                    )
                }
            }

            val finishedAt = clock.now()
            var changesCount = 0
            var itemsFetched = 0
            var firstError: String? = null

            for (outcome in outcomes) {
                val gradeChanges = if (outcome.grades != null) {
                    detector.diffGrades(
                        stored = store.listGrades(includeDeleted = true, childId = outcome.childId),
                        fresh = outcome.grades,
                        complete = true,
                    )
                } else {
                    Changes()
                }

                val messageChanges = if (outcome.messages != null) {
                    detector.diffMessages(
                        stored = store.listMessages(includeDeleted = true, limit = 10_000, childId = outcome.childId),
                        fresh = outcome.messages,
                        complete = true,
                    )
                } else {
                    Changes()
                }

                changesCount += store.applyChanges(
                    syncRunId = runId,
                    gradeChanges = gradeChanges,
                    messageChanges = messageChanges,
                    now = finishedAt,
                    childId = outcome.childId,
                )
                itemsFetched += (outcome.grades?.size ?: 0) + (outcome.messages?.size ?: 0)
                if (firstError == null) {
                    firstError = outcome.gradesError ?: outcome.messagesError
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
                SyncResult.Failed(firstError)
            } else {
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
                SyncResult.Ok(changesCount)
            }
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
            store.finishSyncRun(
                id = runId,
                finishedAt = clock.now(),
                status = SyncRunStatus.ERROR,
                errorCode = code,
                itemsFetched = 0,
                changesDetected = 0,
            )
            logger.warn("Sync failed: {}", code)
            SyncResult.Failed(code)
        } catch (e: Throwable) {
            val code = "unexpected_error"
            consecutiveErrors += 1
            store.finishSyncRun(
                id = runId,
                finishedAt = clock.now(),
                status = SyncRunStatus.ERROR,
                errorCode = code,
                itemsFetched = 0,
                changesDetected = 0,
            )
            logger.error("Unexpected sync error", e)
            SyncResult.Failed(code)
        }
    }

    private fun headerMatches(existing: StoredMessage, header: school.model.MessageHeader): Boolean =
        existing.deletedAt == null &&
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
        val messagesError: String?,
    )
}
