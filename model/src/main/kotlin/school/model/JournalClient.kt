package school.model

/** Provider-neutral boundary to the e-journal (ADR 0006). */
interface JournalClient {
    /**
     * @throws AuthRejected wrong login/password or interactive step required
     * @throws JournalUnavailable network/5xx/layout
     */
    fun login()

    fun listChildren(): List<ChildAccount> = emptyList()

    /**
     * Returns the child's grades, or `null` when the e-journal denies access to the grades resource for
     * this account (e.g. a kindergarten child). `null` means "unknown", not "no grades": callers must not
     * treat it as a complete, empty listing.
     */
    fun fetchGrades(childId: String? = null): List<Grade>?

    fun fetchMessageHeaders(childId: String? = null): MessageHeaderListing

    fun fetchMessage(sourceId: String, childId: String? = null): Message

    /**
     * Returns the school's notice-board announcements as [Message]s with [MessageType.ANNOUNCEMENT]
     * (ADR 0015), or `null` when they are not available for this account. Like [fetchGrades], `null`
     * means "unknown", never "no announcements".
     */
    fun fetchAnnouncements(childId: String? = null): List<Message>? = null
}

/**
 * Message headers from the inbox. [complete] is `false` when the listing was cut at a safety limit, so
 * missing items must not be treated as deleted.
 */
data class MessageHeaderListing(val headers: List<MessageHeader>, val complete: Boolean = true)
