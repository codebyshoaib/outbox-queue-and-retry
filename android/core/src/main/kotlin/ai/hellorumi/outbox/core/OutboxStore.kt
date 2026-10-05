package ai.hellorumi.outbox.core

/** Persistence port. Every method is ONE transaction: it fully happens or not at all. */
interface OutboxStore {
    /** Insert the message and its outbox row together. */
    fun enqueue(message: Message, row: OutboxRow)

    /** Every outbox row with its message, in the order rows were inserted (oldest first). */
    fun outboxRows(): List<Pair<Message, OutboxRow>>

    /** Message -> SENT with [serverSeq], outbox row deleted. */
    fun markSent(messageId: String, serverSeq: Long)

    /** Row gets [attempts] and [nextAttemptAt]; the message stays PENDING. */
    fun reschedule(messageId: String, attempts: Int, nextAttemptAt: Long)

    /** Message -> FAILED, outbox row deleted. */
    fun markFailed(messageId: String)

    /**
     * If the message is FAILED: -> PENDING plus a fresh row (attempts 0, due and created [now], so it
     * queues behind its chat's other rows). Returns false and changes nothing otherwise.
     */
    fun requeue(messageId: String, now: Long): Boolean
}
