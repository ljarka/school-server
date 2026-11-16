package school.server.store

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import school.model.Attachment
import school.model.ChangeType
import school.model.EntityType
import school.model.Grade
import school.model.GradeKind
import school.model.Message
import school.model.MessageHeader
import school.model.SyncRunStatus
import school.server.store.db.SchoolDatabase

data class StoredGrade(
    val grade: Grade,
    val contentHash: String,
    val firstSeenAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

data class StoredMessage(
    val sourceId: String,
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

    fun upsertGrade(
        grade: Grade,
        contentHash: String,
        now: Instant,
        firstSeenAt: Instant = now,
        deletedAt: Instant? = null,
    ) {
        db.schemaQueries.upsertGrade(
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
            first_seen_at = firstSeenAt.toString(),
            updated_at = now.toString(),
            deleted_at = deletedAt?.toString(),
        )
    }

    fun softDeleteGrade(sourceId: String, now: Instant) {
        db.schemaQueries.softDeleteGrade(
            deleted_at = now.toString(),
            updated_at = now.toString(),
            source_id = sourceId,
        )
    }

    fun getGrade(sourceId: String): StoredGrade? =
        db.schemaQueries.selectGradeById(sourceId).executeAsOneOrNull()?.toStoredGrade()

    fun listGrades(
        semester: Int? = null,
        subject: String? = null,
        includeDeleted: Boolean = false,
    ): List<StoredGrade> = db.schemaQueries.listGrades(
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
    ) {
        db.schemaQueries.upsertMessage(
            source_id = header.sourceId,
            sender = header.sender,
            subject = header.subject,
            body_text = null,
            body_html = null,
            sent_at = header.sentAt.toString(),
            attachments = json.encodeToString(attachments),
            content_hash = contentHash,
            read_at = null,
            first_seen_at = firstSeenAt.toString(),
            updated_at = now.toString(),
            deleted_at = deletedAt?.toString(),
        )
    }

    fun upsertMessage(
        message: Message,
        contentHash: String,
        now: Instant,
        firstSeenAt: Instant = now,
        deletedAt: Instant? = null,
    ) {
        db.schemaQueries.upsertMessage(
            source_id = message.sourceId,
            sender = message.sender,
            subject = message.subject,
            body_text = message.bodyText,
            body_html = message.bodyHtml,
            sent_at = message.sentAt.toString(),
            attachments = json.encodeToString(message.attachments),
            content_hash = contentHash,
            read_at = null,
            first_seen_at = firstSeenAt.toString(),
            updated_at = now.toString(),
            deleted_at = deletedAt?.toString(),
        )
    }

    fun softDeleteMessage(sourceId: String, now: Instant) {
        db.schemaQueries.softDeleteMessage(
            deleted_at = now.toString(),
            updated_at = now.toString(),
            source_id = sourceId,
        )
    }

    fun getMessage(sourceId: String): StoredMessage? =
        db.schemaQueries.selectMessageById(sourceId).executeAsOneOrNull()?.toStoredMessage()

    fun listMessages(
        unreadOnly: Boolean = false,
        includeDeleted: Boolean = false,
        limit: Int = 50,
        offset: Int = 0,
    ): List<StoredMessage> = db.schemaQueries.listMessages(
        includeDeleted = if (includeDeleted) 1L else 0L,
        unreadOnly = if (unreadOnly) 1L else 0L,
        limit = limit.toLong(),
        offset = offset.toLong(),
    ).executeAsList().map { it.toStoredMessage() }

    fun markMessageRead(sourceId: String, now: Instant) {
        db.schemaQueries.markMessageRead(readAt = now.toString(), sourceId = sourceId)
    }

    fun markMessageUnread(sourceId: String) {
        db.schemaQueries.markMessageUnread(source_id = sourceId)
    }

    fun markAllMessagesRead(now: Instant): Long {
        val unread = countUnreadMessages()
        db.schemaQueries.markAllMessagesRead(read_at = now.toString())
        return unread
    }

    fun countActiveMessages(): Long = db.schemaQueries.countActiveMessages().executeAsOne()

    fun countUnreadMessages(): Long = db.schemaQueries.countUnreadMessages().executeAsOne()

    fun insertSyncRun(startedAt: Instant): Long {
        db.schemaQueries.insertSyncRun(started_at = startedAt.toString(), status = "running")
        return db.schemaQueries.lastSyncRunId().executeAsOne()
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
            finished_at = finishedAt.toString(),
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
    ) {
        db.schemaQueries.insertChangeEvent(
            entity_type = entityType.wireName(),
            entity_id = entityId,
            change_type = changeType.wireName(),
            diff = diffJson,
            detected_at = detectedAt.toString(),
            sync_run_id = syncRunId,
        )
    }

    fun listChangeEventsAfter(
        afterId: Long,
        entityType: EntityType? = null,
        limit: Int = 100,
    ): List<StoredChangeEvent> = db.schemaQueries.listChangeEventsAfter(
        afterId = afterId,
        entityType = entityType?.wireName(),
        limit = limit.toLong(),
    ).executeAsList().map { it.toStoredChangeEvent() }

    fun listChangeEventsSince(
        since: Instant,
        entityType: EntityType? = null,
        limit: Int = 100,
    ): List<StoredChangeEvent> = db.schemaQueries.listChangeEventsSince(
        since = since.toString(),
        entityType = entityType?.wireName(),
        limit = limit.toLong(),
    ).executeAsList().map { it.toStoredChangeEvent() }

    fun lastChangeEventId(): Long = db.schemaQueries.lastChangeEventId().executeAsOne()

    fun getMeta(key: String): String? = db.schemaQueries.getMeta(key).executeAsOneOrNull()

    fun setMeta(key: String, value: String) {
        db.schemaQueries.setMeta(key, value)
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
        contentHash = content_hash,
        firstSeenAt = Instant.parse(first_seen_at),
        updatedAt = Instant.parse(updated_at),
        deletedAt = deleted_at?.let(Instant::parse),
    )

    private fun school.server.store.db.Message.toStoredMessage(): StoredMessage = StoredMessage(
        sourceId = source_id,
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
