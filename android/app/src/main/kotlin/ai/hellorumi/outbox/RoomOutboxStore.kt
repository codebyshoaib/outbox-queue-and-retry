package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Message
import ai.hellorumi.outbox.core.OutboxRow
import ai.hellorumi.outbox.core.OutboxStore
import ai.hellorumi.outbox.core.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomOutboxStore(private val db: AppDb) : OutboxStore {
    private val dao = db.dao()

    override fun enqueue(message: Message, row: OutboxRow) = db.runInTransaction {
        dao.insertMessage(message.run { MessageEntity(id, chatId, body, createdAt, status, serverSeq) })
        dao.insertRow(row.run { OutboxEntity(messageId = messageId, chatId = chatId, attempts = attempts, nextAttemptAt = nextAttemptAt, createdAt = createdAt) })
    }

    override fun outboxRows() = dao.queued().map { it.message.toMessage() to it.row.toRow() }

    override fun markSent(messageId: String, serverSeq: Long) = db.runInTransaction {
        dao.setSent(messageId, serverSeq)
        dao.deleteRow(messageId)
    }

    // One UPDATE, so a single statement is already atomic.
    override fun reschedule(messageId: String, attempts: Int, nextAttemptAt: Long) =
        dao.reschedule(messageId, attempts, nextAttemptAt)

    override fun markFailed(messageId: String) = db.runInTransaction {
        dao.setStatusIf(messageId, Status.PENDING, Status.FAILED)
        dao.deleteRow(messageId)
    }

    /** The FAILED check is the conditional UPDATE itself, inside the transaction: two taps cannot both win. */
    override fun requeue(messageId: String, now: Long): Boolean = db.runInTransaction<Boolean> {
        if (dao.setStatusIf(messageId, Status.FAILED, Status.PENDING) == 0) return@runInTransaction false
        val chatId = dao.message(messageId)!!.chatId
        dao.insertRow(OutboxEntity(messageId = messageId, chatId = chatId, attempts = 0, nextAttemptAt = now, createdAt = now))
        true
    }

    fun observe(chatId: String): Flow<List<Message>> = dao.observe(chatId).map { l -> l.map { it.toMessage() } }
}
