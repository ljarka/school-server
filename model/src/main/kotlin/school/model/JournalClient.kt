package school.model

/** Provider-neutral boundary to the e-journal (ADR 0006). */
interface JournalClient {
    /**
     * @throws AuthRejected wrong login/password or interactive step required
     * @throws JournalUnavailable network/5xx/layout
     */
    fun login()

    fun fetchGrades(): List<Grade>

    fun fetchMessageHeaders(): List<MessageHeader>

    fun fetchMessage(sourceId: String): Message
}
