package ai.hellorumi.outbox.core

enum class Status { PENDING, SENT, FAILED }

/** [id] is the client_msg_id: made once on send, never regenerated. [serverSeq] is set when acked. */
data class Message(
    val id: String,
    val chatId: String,
    val body: String,
    val createdAt: Long,
    val status: Status,
    val serverSeq: Long? = null,
)

data class OutboxRow(
    val messageId: String,
    val chatId: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
)

/** Epoch millis. Injected so tests control time (P8). */
fun interface Clock {
    fun now(): Long
}
