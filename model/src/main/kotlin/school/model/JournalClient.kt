package school.model

/** Provider-neutral boundary to the e-journal (ADR 0006). */
interface JournalClient {
    /**
     * @throws AuthRejected wrong login/password or interactive step required
     * @throws JournalUnavailable network/5xx/layout
     */
    fun login()

    fun listChildren(): List<ChildAccount> = emptyList()

    fun fetchGrades(childId: String? = null): List<Grade>

    fun fetchMessageHeaders(childId: String? = null): List<MessageHeader>

    fun fetchMessage(sourceId: String, childId: String? = null): Message
}
