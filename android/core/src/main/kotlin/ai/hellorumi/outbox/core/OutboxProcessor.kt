package ai.hellorumi.outbox.core

import kotlin.random.Random

class OutboxProcessor(
    private val store: OutboxStore,
    private val transport: Transport,
    private val clock: Clock,
    private val deviceId: String,
    private val random: () -> Double = { Random.nextDouble() },
) {
    /**
     * One pass over the outbox, oldest row first. A chat stops for this pass as soon as its oldest
     * row is not due or fails transiently, so nothing overtakes it (P2). Not reentrant: callers
     * must run one pass at a time (WorkManager unique work). Returns the earliest time a chat's
     * oldest row is due next, or null if the outbox is empty.
     */
    fun drainOnce(): Long? {
        // ponytail: one send at a time across all chats; chats are independent and could be
        // drained in parallel (one coroutine per chat) if throughput ever matters.
        val blockedChats = mutableSetOf<String>()
        for ((message, row) in store.outboxRows()) {
            if (message.chatId in blockedChats) continue
            if (row.nextAttemptAt > clock.now()) { blockedChats += message.chatId; continue }
            when (val result = transport.send(deviceId, message)) {
                is SendResult.Ok -> store.markSent(message.id, result.serverSeq)
                else -> if (result.isTransient()) {
                    val attempts = row.attempts + 1
                    store.reschedule(message.id, attempts, clock.now() + Backoff.delayMs(attempts, random()))
                    blockedChats += message.chatId
                } else {
                    store.markFailed(message.id)
                }
            }
        }
        // Only a chat's oldest row can go next; rows queued behind it would wake us for nothing.
        return store.outboxRows().distinctBy { it.first.chatId }.minOfOrNull { it.second.nextAttemptAt }
    }
}
