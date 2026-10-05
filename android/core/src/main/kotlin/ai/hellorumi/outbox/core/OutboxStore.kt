package ai.hellorumi.outbox.core

/** Persistence port. Every method is ONE transaction: it fully happens or not at all. */
interface OutboxStore {
    /** Insert the message and its outbox row together. */
    fun enqueue(message: Message, row: OutboxRow)
}
