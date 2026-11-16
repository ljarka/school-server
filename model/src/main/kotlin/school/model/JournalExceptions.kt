package school.model

/** Thrown when the e-journal rejects the configured credentials or requires an interactive step. */
class AuthRejected(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the e-journal cannot be reached, returns 5xx, or changes its response shape. */
class JournalUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
