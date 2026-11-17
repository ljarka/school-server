package school.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FakeClock(var current: Instant = Instant.parse("2026-11-16T12:00:00Z")) : Clock {
    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current += duration
    }
}

class WrongKeyLimiterTest {
    @Test
    fun `blocks after 10 wrong attempts and unblocks after 15 minutes`() {
        val clock = FakeClock()
        val limiter = WrongKeyLimiter(clock = clock)

        repeat(10) {
            assertNull(limiter.blockedRetryAfterSeconds("1.2.3.4"))
            limiter.recordWrongKey("1.2.3.4")
        }

        val retryAfter = limiter.blockedRetryAfterSeconds("1.2.3.4")
        assertNotNull(retryAfter)
        assertEquals(900L, retryAfter)
        assertNull(limiter.blockedRetryAfterSeconds("5.6.7.8"))

        clock.advance(15.minutes)
        assertNull(limiter.blockedRetryAfterSeconds("1.2.3.4"))
    }

    @Test
    fun `caps tracked addresses by dropping oldest`() {
        val clock = FakeClock()
        val limiter = WrongKeyLimiter(clock = clock, maxTrackedAddresses = 2)

        limiter.recordWrongKey("ip-1")
        limiter.recordWrongKey("ip-2")
        limiter.recordWrongKey("ip-3")

        // Adding a 3rd address when max is 2 evicts the oldest ("ip-1")
        repeat(9) { limiter.recordWrongKey("ip-1") }
        assertNull(limiter.blockedRetryAfterSeconds("ip-1"))
    }
}
