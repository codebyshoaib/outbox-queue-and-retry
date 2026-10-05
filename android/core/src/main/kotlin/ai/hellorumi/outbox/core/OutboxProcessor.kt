package ai.hellorumi.outbox.core

import kotlin.random.Random

class OutboxProcessor(
    private val store: OutboxStore,
    private val transport: Transport,
    private val clock: Clock,
    private val deviceId: String,
    private val random: () -> Double = { Random.nextDouble() },
) {
    /** One pass over the outbox. Returns the earliest time a row is due next, or null if empty. */
    fun drainOnce(): Long? {
        for ((message, row) in store.outboxRows()) {
            if (row.nextAttemptAt > clock.now()) continue
            when (val result = transport.send(deviceId, message)) {
                is SendResult.Ok -> store.markSent(message.id, result.serverSeq)
                else -> {
                    val attempts = row.attempts + 1
                    store.reschedule(message.id, attempts, clock.now() + Backoff.delayMs(attempts, random()))
                }
            }
        }
        return store.outboxRows().minOfOrNull { it.second.nextAttemptAt }
    }
}
