package school.server.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Sliding-window rate limiter for wrong API key attempts per client address.
 */
class WrongKeyLimiter(
    private val clock: Clock = Clock.System,
    private val window: Duration = 15.minutes,
    private val maxAttempts: Int = 10,
    private val maxTrackedAddresses: Int = 10_000,
) {
    private val attempts = LinkedHashMap<String, ArrayDeque<Instant>>()

    /**
     * Returns `Retry-After` in seconds if [address] is currently blocked, or `null` otherwise.
     */
    @Synchronized
    fun blockedRetryAfterSeconds(address: String): Long? {
        val now = clock.now()
        val queue = attempts[address] ?: return null
        prune(queue, now)
        if (queue.isEmpty()) {
            attempts.remove(address)
            return null
        }
        if (queue.size < maxAttempts) {
            return null
        }
        val oldest = queue.first()
        val unblockAt = oldest + window
        val remainingSeconds = (unblockAt - now).inWholeSeconds
        return maxOf(1L, remainingSeconds)
    }

    @Synchronized
    fun recordWrongKey(address: String) {
        val now = clock.now()
        val existing = attempts[address]
        if (existing != null) {
            prune(existing, now)
            existing.addLast(now)
            return
        }
        if (attempts.size >= maxTrackedAddresses) {
            pruneStaleOrOldest(now)
        }
        attempts[address] = ArrayDeque<Instant>().apply { addLast(now) }
    }

    private fun prune(queue: ArrayDeque<Instant>, now: Instant) {
        val cutoff = now - window
        while (queue.isNotEmpty() && queue.first() <= cutoff) {
            queue.removeFirst()
        }
    }

    private fun pruneStaleOrOldest(now: Instant) {
        val iterator = attempts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            prune(entry.value, now)
            if (entry.value.isEmpty()) {
                iterator.remove()
            }
        }
        while (attempts.size >= maxTrackedAddresses && attempts.isNotEmpty()) {
            val firstKey = attempts.keys.first()
            attempts.remove(firstKey)
        }
    }
}
