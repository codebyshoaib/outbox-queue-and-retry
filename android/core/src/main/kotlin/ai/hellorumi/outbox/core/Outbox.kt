package ai.hellorumi.outbox.core

/** The tap path: saves locally and returns. Has no transport, so it cannot touch the network. */
class Outbox(private val store: OutboxStore, private val clock: Clock) {
    fun send(chatId: String, body: String): Message {
        val now = clock.now()
        val message = Message("", chatId, body, now, Status.PENDING)
        store.enqueue(message, OutboxRow(message.id, chatId, attempts = 0, nextAttemptAt = now, createdAt = now))
        return message
    }
}
