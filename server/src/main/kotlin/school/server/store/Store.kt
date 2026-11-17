package school.server.store

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import school.model.Attachment
import school.model.ChangeType
import school.model.ChildAccount
import school.model.EntityType
import school.model.Grade
import school.model.GradeKind
import school.model.Message
import school.model.MessageHeader
import school.model.SyncRunStatus
import school.server.store.db.SchoolDatabase
import school.server.sync.Changes

data class StoredGrade(
    val grade: Grade,
    val childId: String = "default",
    val contentHash: String,
    val firstSeenAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

data class StoredMessage(
    val sourceId: String,
    val childId: String = "default",
    val sender: String,
    val subject: String,
    val bodyText: String?,
    val bodyHtml: String?,
    val sentAt: Instant,
    val attachments: List<Attachment>,
    val contentHash: String,
    val readAt: Instant?,
    val firstSeenAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
}

data class StoredChangeEvent(
    val id: Long,
    val childId: String = "default",
    val entityType: EntityType,
    val entityId: String,
    val changeType: ChangeType,
    val diffJson: String?,
    val detectedAt: Instant,
    val syncRunId: Long,
)

data class StoredSyncRun(
    val id: Long,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val status: SyncRunStatus,
    val errorCode: String?,
    val itemsFetched: Int,
    val changesDetected: Int,
)

class Store(private val db: SchoolDatabase) {
    private val json = Json

    fun <T> transaction(block: Store.() -> T): T = db.transactionWithResult {
        block()
    }

    fun upsertChildAccounts(children: List<ChildAccount>, now: Instant) = transaction {
        for (child in children) {
            db.schemaQueries.upsertChildAccount(
                id = child.id,
                login = child.login,
                name = child.name,
                updated_at = now.toDb(),
            )
        }
    }

    fun listChildAccounts(): List<ChildAccount> = db.schemaQueries.listChildAccounts().executeAsList().map {
        ChildAccount(id = it.id, login = it.login, name = it.name)
    }

    /**
     * Finds the child for an API `?child=` selector (id or login). Also accepts a raw child id that has
     * stored data but no account row (e.g. the single-child "default"). Returns `null` when unknown.
     */
    fun findChildId(selector: String): String? {
        val trimmed = selector.trim()
        if (trimmed.isEmpty()) return null
        db.schemaQueries.findChildAccount(trimmed).executeAsOneOrNull()?.let { return it.id }
        return trimmed.takeIf { db.schemaQueries.hasDataForChild(it).executeAsOne() }
    }

    fun upsertGrade(
        grade: Grade,
        contentHash: String,
        now: Instant,
        firstSeenAt: Instant = now,
        deletedAt: Instant? = null,
        childId: String = "default",
    ) {
        db.schemaQueries.upsertGrade(
            child_id = childId,
            source_id = grade.sourceId,
            subject = grade.subject,
            value_ = grade.value,
            numeric_value = grade.numericValue,
            weight = grade.weight?.toLong(),
            category = grade.category,
            semester = grade.semester.toLong(),
            kind = grade.kind.wireName(),
            counts_to_average = if (grade.countsToAverage) 1L else 0L,
            teacher = grade.teacher,
            comment = grade.comment,
            issued_on = grade.issuedOn.toString(),
            content_hash = contentHash,
            first_seen_at = firstSeenAt.toDb(),
            updated_at = now.toDb(),
            deleted_at = deletedAt?.toDb(),
        )
    }

    fun softDeleteGrade(sourceId: String, now: Instant, childId: String = "default") {
        db.schemaQueries.softDeleteGrade(
            deleted_at = now.toDb(),
            updated_at = now.toDb(),
            child_id = childId,
            source_id = sourceId,
        )
    }

    fun getGrade(sourceId: String, childId: String = "default"): StoredGrade? =
        db.schemaQueries.selectGradeById(child_id = childId, source_id = sourceId)
            .executeAsOneOrNull()
            ?.toStoredGrade()

    fun listGrades(
        semester: Int? = null,
        subject: String? = null,
        includeDeleted: Boolean = false,
        childId: String? = null,
    ): List<StoredGrade> = db.schemaQueries.listGrades(
        childId = childId,
        includeDeleted = if (includeDeleted) 1L else 0L,
        semester = semester?.toLong(),
        subject = subject,
    ).executeAsList().map { it.toStoredGrade() }

    fun countActiveGrades(): Long = db.schemaQueries.countActiveGrades().executeAsOne()

    fun upsertMessageHeader(
        header: MessageHeader,
        attachments: List<Attachment>,
        contentHash: String,
        now: Instant,
        firstSeenAt: Instant = now,
        deletedAt: Instant? = null,
        childId: String = "default",
    ) {
        db.schemaQueries.upsertMessage(
            child_id = childId,
            source_id = header.sourceId,
            sender = header.sender,
            subject = header.subject,
            body_text = null,
            body_html = null,
            sent_at = header.sentAt.toDb(),
            attachments = json.encodeToString(attachments),
            content_hash = contentHash,
            read_at = null,
            first_seen_at = firstSeenAt.toDb(),
            updated_at = now.toDb(),
            deleted_at = deletedAt?.toDb(),
        )
    }

    fun upsertMessage(
        message: Message,
        contentHash: String,
        now: Instant,
        firstSeenAt: Instant = now,
        deletedAt: Instant? = null,
        childId: String = "default",
    ) {
        db.schemaQueries.upsertMessage(
            child_id = childId,
            source_id = message.sourceId,
            sender = message.sender,
            subject = message.subject,
            body_text = message.bodyText,
            body_html = message.bodyHtml,
            sent_at = message.sentAt.toDb(),
            attachments = json.encodeToString(message.attachments),
            content_hash = contentHash,
            read_at = null,
            first_seen_at = firstSeenAt.toDb(),
            updated_at = now.toDb(),
            deleted_at = deletedAt?.toDb(),
        )
    }

    fun softDeleteMessage(sourceId: String, now: Instant, childId: String = "default") {
        db.schemaQueries.softDeleteMessage(
            deleted_at = now.toDb(),
            updated_at = now.toDb(),
            child_id = childId,
            source_id = sourceId,
        )
    }

    fun getMessage(sourceId: String, childId: String? = null): StoredMessage? =
        db.schemaQueries.selectMessageById(sourceId = sourceId, childId = childId)
            .executeAsOneOrNull()
            ?.toStoredMessage()

    fun listMessages(
        unreadOnly: Boolean = false,
        includeDeleted: Boolean = false,
        limit: Int = 50,
        offset: Int = 0,
        childId: String? = null,
    ): List<StoredMessage> = db.schemaQueries.listMessages(
        childId = childId,
        includeDeleted = if (includeDeleted) 1L else 0L,
        unreadOnly = if (unreadOnly) 1L else 0L,
        limit = limit.toLong(),
        offset = offset.toLong(),
    ).executeAsList().map { it.toStoredMessage() }

    /** One page of messages, newest first, using keyset pagination on (sentAt, sourceId). */
    fun listMessagesPage(
        unreadOnly: Boolean,
        includeDeleted: Boolean,
        since: Instant?,
        after: Pair<Instant, String>?,
        limit: Int,
        childId: String?,
    ): List<StoredMessage> = db.schemaQueries.listMessagesPage(
        childId = childId,
        includeDeleted = if (includeDeleted) 1L else 0L,
        unreadOnly = if (unreadOnly) 1L else 0L,
        since = since?.toDb(),
        cursorSentAt = after?.first?.toDb(),
        cursorSourceId = after?.second.orEmpty(),
        limit = limit.toLong(),
    ).executeAsList().map { it.toStoredMessage() }

    fun markMessageRead(sourceId: String, now: Instant, childId: String? = null) {
        db.schemaQueries.markMessageRead(readAt = now.toDb(), sourceId = sourceId, childId = childId)
    }

    fun markMessageUnread(sourceId: String, childId: String? = null) {
        db.schemaQueries.markMessageUnread(sourceId = sourceId, childId = childId)
    }

    /** Marks all unread messages as read and returns how many were changed (atomic, one statement). */
    fun markAllMessagesRead(now: Instant, childId: String? = null): Long =
        db.schemaQueries.markAllMessagesRead(readAt = now.toDb(), childId = childId).executeAsList().size.toLong()

    fun countActiveMessages(): Long = db.schemaQueries.countActiveMessages().executeAsOne()

    fun countUnreadMessages(): Long = db.schemaQueries.countUnreadMessages().executeAsOne()

    fun insertSyncRun(startedAt: Instant): Long {
        db.schemaQueries.insertSyncRun(started_at = startedAt.toDb(), status = "running")
        return db.schemaQueries.lastSyncRunId().executeAsOne()
    }

    /** Closes runs left as "running" by a crash or a hard stop. Call once at startup. */
    fun failInterruptedSyncRuns(now: Instant) {
        db.schemaQueries.failInterruptedSyncRuns(finishedAt = now.toDb())
    }

    fun finishSyncRun(
        id: Long,
        finishedAt: Instant,
        status: SyncRunStatus,
        errorCode: String?,
        itemsFetched: Int,
        changesDetected: Int,
    ) {
        db.schemaQueries.finishSyncRun(
            finished_at = finishedAt.toDb(),
            status = status.wireName(),
            error_code = errorCode,
            items_fetched = itemsFetched.toLong(),
            changes_detected = changesDetected.toLong(),
            id = id,
        )
    }

    fun getLastSyncRun(): StoredSyncRun? = db.schemaQueries.selectLastSyncRun().executeAsOneOrNull()?.toStoredSyncRun()

    fun getLastSuccessfulSyncRun(): StoredSyncRun? =
        db.schemaQueries.selectLastSuccessfulSyncRun().executeAsOneOrNull()?.toStoredSyncRun()

    fun insertChangeEvent(
        entityType: EntityType,
        entityId: String,
        changeType: ChangeType,
        diffJson: String?,
        detectedAt: Instant,
        syncRunId: Long,
        childId: String = "default",
    ) {
        db.schemaQueries.insertChangeEvent(
            entity_type = entityType.wireName(),
            entity_id = entityId,
            change_type = changeType.wireName(),
            diff = diffJson,
            detected_at = detectedAt.toDb(),
            sync_run_id = syncRunId,
            child_id = childId,
        )
    }

    fun listChangeEventsAfter(
        afterId: Long,
        entityType: EntityType? = null,
        limit: Int = 100,
        childId: String? = null,
    ): List<StoredChangeEvent> = db.schemaQueries.listChangeEventsAfter(
        afterId = afterId,
        childId = childId,
        entityType = entityType?.wireName(),
        limit = limit.toLong(),
    ).executeAsList().map { it.toStoredChangeEvent() }

    fun listChangeEventsSince(
        since: Instant,
        entityType: EntityType? = null,
        limit: Int = 100,
        childId: String? = null,
    ): List<StoredChangeEvent> = db.schemaQueries.listChangeEventsSince(
        since = since.toDb(),
        childId = childId,
        entityType = entityType?.wireName(),
        limit = limit.toLong(),
    ).executeAsList().map { it.toStoredChangeEvent() }

    fun lastChangeEventId(): Long = db.schemaQueries.lastChangeEventId().executeAsOne()

    fun getMeta(key: String): String? = db.schemaQueries.getMeta(key).executeAsOneOrNull()

    fun setMeta(key: String, value: String) {
        db.schemaQueries.setMeta(key, value)
    }

    fun applyChanges(
        syncRunId: Long,
        gradeChanges: Changes<Grade>,
        messageChanges: Changes<Message>,
        now: Instant,
        childId: String = "default",
    ): Int = transaction {
        for (created in gradeChanges.created) {
            upsertGrade(
                created.item,
                contentHash = created.contentHash,
                now = now,
                firstSeenAt = now,
                childId = childId,
            )
            insertChangeEvent(
                entityType = EntityType.GRADE,
                entityId = created.item.sourceId,
                changeType = ChangeType.CREATED,
                diffJson = null,
                detectedAt = now,
                syncRunId = syncRunId,
                childId = childId,
            )
        }
        for (updated in gradeChanges.updated) {
            upsertGrade(
                updated.item,
                contentHash = updated.contentHash,
                now = now,
                firstSeenAt = updated.firstSeenAt,
                childId = childId,
            )
            insertChangeEvent(
                entityType = EntityType.GRADE,
                entityId = updated.item.sourceId,
                changeType = ChangeType.UPDATED,
                diffJson = updated.diffJson,
                detectedAt = now,
                syncRunId = syncRunId,
                childId = childId,
            )
        }
        for (deletedId in gradeChanges.deletedIds) {
            softDeleteGrade(deletedId, now = now, childId = childId)
            insertChangeEvent(
                entityType = EntityType.GRADE,
                entityId = deletedId,
                changeType = ChangeType.DELETED,
                diffJson = null,
                detectedAt = now,
                syncRunId = syncRunId,
                childId = childId,
            )
        }

        for (created in messageChanges.created) {
            upsertMessage(
                created.item,
                contentHash = created.contentHash,
                now = now,
                firstSeenAt = now,
                childId = childId,
            )
            insertChangeEvent(
                entityType = EntityType.MESSAGE,
                entityId = created.item.sourceId,
                changeType = ChangeType.CREATED,
                diffJson = null,
                detectedAt = now,
                syncRunId = syncRunId,
                childId = childId,
            )
        }
        for (updated in messageChanges.updated) {
            upsertMessage(
                updated.item,
                contentHash = updated.contentHash,
                now = now,
                firstSeenAt = updated.firstSeenAt,
                childId = childId,
            )
            insertChangeEvent(
                entityType = EntityType.MESSAGE,
                entityId = updated.item.sourceId,
                changeType = ChangeType.UPDATED,
                diffJson = updated.diffJson,
                detectedAt = now,
                syncRunId = syncRunId,
                childId = childId,
            )
        }
        for (deletedId in messageChanges.deletedIds) {
            softDeleteMessage(deletedId, now = now, childId = childId)
            insertChangeEvent(
                entityType = EntityType.MESSAGE,
                entityId = deletedId,
                changeType = ChangeType.DELETED,
                diffJson = null,
                detectedAt = now,
                syncRunId = syncRunId,
                childId = childId,
            )
        }

        gradeChanges.totalCount + messageChanges.totalCount
    }

    private fun school.server.store.db.Grade.toStoredGrade(): StoredGrade = StoredGrade(
        grade = Grade(
            sourceId = source_id,
            subject = subject,
            value = value_,
            numericValue = numeric_value,
            weight = weight?.toInt(),
            category = category,
            semester = semester.toInt(),
            kind = parseGradeKind(kind),
            countsToAverage = counts_to_average != 0L,
            teacher = teacher,
            comment = comment,
            issuedOn = LocalDate.parse(issued_on),
        ),
        childId = child_id,
        contentHash = content_hash,
        firstSeenAt = Instant.parse(first_seen_at),
        updatedAt = Instant.parse(updated_at),
        deletedAt = deleted_at?.let(Instant::parse),
    )

    private fun school.server.store.db.Message.toStoredMessage(): StoredMessage = StoredMessage(
        sourceId = source_id,
        childId = child_id,
        sender = sender,
        subject = subject,
        bodyText = body_text,
        bodyHtml = body_html,
        sentAt = Instant.parse(sent_at),
        attachments = json.decodeFromString(attachments),
        contentHash = content_hash,
        readAt = read_at?.let(Instant::parse),
        firstSeenAt = Instant.parse(first_seen_at),
        updatedAt = Instant.parse(updated_at),
        deletedAt = deleted_at?.let(Instant::parse),
    )

    private fun school.server.store.db.Change_event.toStoredChangeEvent(): StoredChangeEvent = StoredChangeEvent(
        id = id,
        childId = child_id,
        entityType = parseEntityType(entity_type),
        entityId = entity_id,
        changeType = parseChangeType(change_type),
        diffJson = diff,
        detectedAt = Instant.parse(detected_at),
        syncRunId = sync_run_id,
    )

    private fun school.server.store.db.Sync_run.toStoredSyncRun(): StoredSyncRun = StoredSyncRun(
        id = id,
        startedAt = Instant.parse(started_at),
        finishedAt = finished_at?.let(Instant::parse),
        status = parseSyncRunStatus(status),
        errorCode = error_code,
        itemsFetched = items_fetched.toInt(),
        changesDetected = changes_detected.toInt(),
    )
}

private val dbInstantFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Instants are stored with a fixed millisecond format so that text comparison and ORDER BY in SQL match
 * time order (`Instant.toString()` drops the fraction when it is zero, and "…00.5Z" < "…00Z" as text).
 */
internal fun Instant.toDb(): String = dbInstantFormat.format(toJavaInstant())

private fun GradeKind.wireName(): String = when (this) {
    GradeKind.REGULAR -> "regular"
    GradeKind.PROPOSED_SEMESTER -> "proposed_semester"
    GradeKind.SEMESTER -> "semester"
    GradeKind.PROPOSED_FINAL -> "proposed_final"
    GradeKind.FINAL -> "final"
}

private fun parseGradeKind(raw: String): GradeKind = when (raw) {
    "regular" -> GradeKind.REGULAR
    "proposed_semester" -> GradeKind.PROPOSED_SEMESTER
    "semester" -> GradeKind.SEMESTER
    "proposed_final" -> GradeKind.PROPOSED_FINAL
    "final" -> GradeKind.FINAL
    else -> error("Unknown grade kind: $raw")
}

private fun EntityType.wireName(): String = when (this) {
    EntityType.GRADE -> "grade"
    EntityType.MESSAGE -> "message"
}

private fun parseEntityType(raw: String): EntityType = when (raw) {
    "grade" -> EntityType.GRADE
    "message" -> EntityType.MESSAGE
    else -> error("Unknown entity type: $raw")
}

private fun ChangeType.wireName(): String = when (this) {
    ChangeType.CREATED -> "created"
    ChangeType.UPDATED -> "updated"
    ChangeType.DELETED -> "deleted"
}

private fun parseChangeType(raw: String): ChangeType = when (raw) {
    "created" -> ChangeType.CREATED
    "updated" -> ChangeType.UPDATED
    "deleted" -> ChangeType.DELETED
    else -> error("Unknown change type: $raw")
}

private fun SyncRunStatus.wireName(): String = when (this) {
    SyncRunStatus.RUNNING -> "running"
    SyncRunStatus.OK -> "ok"
    SyncRunStatus.ERROR -> "error"
    SyncRunStatus.AUTH_FAILED -> "auth_failed"
}

private fun parseSyncRunStatus(raw: String): SyncRunStatus = when (raw) {
    "running" -> SyncRunStatus.RUNNING
    "ok" -> SyncRunStatus.OK
    "error" -> SyncRunStatus.ERROR
    "auth_failed" -> SyncRunStatus.AUTH_FAILED
    else -> error("Unknown sync status: $raw")
}
