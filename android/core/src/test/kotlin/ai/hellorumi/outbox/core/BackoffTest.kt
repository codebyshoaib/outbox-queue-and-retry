package ai.hellorumi.outbox.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BackoffTest {
    @Test
    fun P5_backoffDoublesFromOneSecondAndCapsAtSixty() {
        val full = (1..8).map { Backoff.delayMs(it, random = 1.0) }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), full)
    }

    @Test
    fun P5_jitterScalesDelayBetweenHalfAndFull() {
        assertEquals(500L, Backoff.delayMs(1, random = 0.0))
        assertEquals(750L, Backoff.delayMs(1, random = 0.5))
        assertEquals(30_000L, Backoff.delayMs(7, random = 0.0))
    }

    @Test
    fun P5_hugeAttemptCountsDoNotOverflow() {
        for (attempts in listOf(31, 32, 63, 64, 1_000, Int.MAX_VALUE)) {
            assertEquals(60_000L, Backoff.delayMs(attempts, random = 1.0), "attempts=$attempts")
        }
    }
}
