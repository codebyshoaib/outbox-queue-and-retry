package ai.hellorumi.outbox.core

object Backoff {
    private const val BASE_MS = 1_000L
    private const val CAP_MS = 60_000L

    /** min(60 s, 1 s x 2^(attempts-1)) x jitter, jitter = 0.5 + 0.5 x [random] (random in [0, 1]). */
    fun delayMs(attempts: Int, random: Double): Long {
        // 2^6 s already exceeds the cap, so clamping the exponent also rules out shift overflow.
        val exp = (attempts - 1).coerceIn(0, 6)
        val base = minOf(CAP_MS, BASE_MS shl exp)
        return (base * (0.5 + 0.5 * random)).toLong()
    }
}
