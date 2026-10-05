package ai.hellorumi.outbox.core

import java.util.UUID

/** The tap path: saves locally and returns. Has no transport, so it cannot touch the network. */
class Outbox(private val store: OutboxStore, private val clock: Clock) {
    fun send(chatId: String, body: String): Message {
        val now = clock.now()
        val message = Message(UUID.randomUUID().toString(), chatId, body, now, Status.PENDING)
        store.enqueue(message, OutboxRow(message.id, chatId, attempts = 0, nextAttemptAt = now, createdAt = now))
        return message
    }

    /** P7: a FAILED message goes back in the outbox, due now, same client_msg_id. False if not failed. */
    fun retry(messageId: String): Boolean = store.requeue(messageId, clock.now())
}
