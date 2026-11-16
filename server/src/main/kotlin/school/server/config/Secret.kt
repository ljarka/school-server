package school.server.config

/**
 * Wraps a sensitive string (password, API key) so `toString()` never leaks it.
 */
class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "***"

    override fun equals(other: Any?): Boolean = other is Secret && value == other.value

    override fun hashCode(): Int = value.hashCode()
}
