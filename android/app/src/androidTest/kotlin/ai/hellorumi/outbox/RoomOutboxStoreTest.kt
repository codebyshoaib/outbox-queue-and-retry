package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Message
import ai.hellorumi.outbox.core.OutboxRow
import ai.hellorumi.outbox.core.Status
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOutboxStoreTest {
    private lateinit var db: AppDb
    private lateinit var store: RoomOutboxStore

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).build()
        store = RoomOutboxStore(db)
    }

    @After fun close() = db.close()

    private fun msg(id: String, chat: String = "c", at: Long = 1_000L) = Message(id, chat, "body $id", at, Status.PENDING)
    private fun row(m: Message) = OutboxRow(m.id, m.chatId, attempts = 0, nextAttemptAt = m.createdAt, createdAt = m.createdAt)
    private fun enqueue(m: Message) = store.enqueue(m, row(m))
    private fun message(id: String) = db.dao().message(id)?.toMessage()

    @Test fun A1_enqueueWritesMessageAndRowTogether() {
        val m = msg("a")
        enqueue(m)
        assertEquals(listOf(m to row(m)), store.outboxRows())
    }

    @Test fun A1_enqueueIsAtomic() {
        // An orphan row with the same message id makes the second insert of enqueue fail.
        val m = msg("a")
        db.dao().insertRow(OutboxEntity(messageId = "a", chatId = "c", attempts = 0, nextAttemptAt = 0, createdAt = 0))
        assertFailsWith<SQLiteConstraintException> { enqueue(m) }
        assertNull(message("a"), "the message insert must roll back with the failed row insert")
    }

    @Test fun A1_outboxRowsKeepInsertionOrder() {
        // Ids and timestamps both sort the other way, so only the insertion key gives this order.
        val ms = listOf(msg("z", at = 3), msg("y", "other", at = 2), msg("x", at = 1))
        ms.forEach(::enqueue)
        assertEquals(listOf("z", "y", "x"), store.outboxRows().map { it.first.id })
    }

    @Test fun A1_markSentSetsSeqAndRemovesRow() {
        enqueue(msg("a"))
        store.markSent("a", 7)
        assertEquals(Status.SENT to 7L, message("a")!!.let { it.status to it.serverSeq })
        assertTrue(store.outboxRows().isEmpty())
    }

    @Test fun A1_rescheduleUpdatesRowAndKeepsMessagePending() {
        val m = msg("a")
        enqueue(m)
        store.reschedule("a", attempts = 2, nextAttemptAt = 5_000)
        assertEquals(listOf(m to row(m).copy(attempts = 2, nextAttemptAt = 5_000)), store.outboxRows())
    }

    @Test fun A1_markFailedSetsFailedAndRemovesRow() {
        enqueue(msg("a"))
        store.markFailed("a")
        assertEquals(Status.FAILED, message("a")!!.status)
        assertTrue(store.outboxRows().isEmpty())
    }

    @Test fun A1_requeueOnlyWhenFailed() {
        enqueue(msg("a"))
        assertFalse(store.requeue("a", now = 9_000), "a pending message is not requeued")
        assertEquals(1, store.outboxRows().size)

        store.markFailed("a")
        assertTrue(store.requeue("a", now = 9_000))
        assertEquals(listOf(Status.PENDING to OutboxRow("a", "c", 0, 9_000, 9_000)),
            store.outboxRows().map { it.first.status to it.second })

        assertFalse(store.requeue("a", now = 9_500), "already pending again")
        assertFalse(store.requeue("missing", now = 9_500))
        assertEquals(1, store.outboxRows().size)
    }

    @Test fun A1_requeuedRowQueuesBehindItsChat() {
        enqueue(msg("a")); store.markFailed("a")
        enqueue(msg("b"))
        store.requeue("a", now = 9_000)
        assertEquals(listOf("b", "a"), store.outboxRows().map { it.first.id })
    }

    @Test fun A1_concurrentRequeuesOfOneFailedMessageMakeOneRow() {
        // The FAILED check and the insert must be one transaction, or two taps both pass the check.
        // Probabilistic: a check outside the transaction does not lose every run.
        repeat(20) { i ->
            val id = "m$i"
            enqueue(msg(id)); store.markFailed(id)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val results = (1..8).map { pool.submit<Boolean> { start.await(); store.requeue(id, now = 9_000) } }
            start.countDown()
            assertEquals(1, results.count { it.get() }, "exactly one requeue wins")
            pool.shutdown()
        }
        assertEquals(20, store.outboxRows().size)
    }

    @Test fun A1_observeEmitsTheChatsMessagesInSendOrder() = runBlocking {
        enqueue(msg("z", at = 2)); enqueue(msg("other", "c2")); enqueue(msg("y", at = 1))
        store.markSent("z", 1)
        val seen = store.observe("c").first()
        assertEquals(listOf("z" to Status.SENT, "y" to Status.PENDING), seen.map { it.id to it.status })
    }
}
