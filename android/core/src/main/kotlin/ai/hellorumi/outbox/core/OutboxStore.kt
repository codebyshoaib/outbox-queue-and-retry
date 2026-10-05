package ai.hellorumi.outbox.core

/** Persistence port. Every method is ONE transaction: it fully happens or not at all. */
interface OutboxStore {
    /** Insert the message and its outbox row together. */
    fun enqueue(message: Message, row: OutboxRow)

    /** Every outbox row with its message, oldest first. */
    fun outboxRows(): List<Pair<Message, OutboxRow>>

    /** Message -> SENT with [serverSeq], outbox row deleted. */
    fun markSent(messageId: String, serverSeq: Long)
}
