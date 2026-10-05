package ai.hellorumi.outbox.core

import kotlin.test.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OutboxTest {
    private val store = FakeStore()
    private val clock = FakeClock(42_000L)
    private val outbox = Outbox(store, clock)

    @Test
    fun P8_sendStampsTimesFromInjectedClock() {
        val msg = outbox.send("chat-1", "hello")

        assertEquals(42_000L, msg.createdAt)
        val row = store.outbox.getValue(msg.id)
        assertEquals(42_000L, row.createdAt)
        assertEquals(42_000L, row.nextAttemptAt, "a new row is due immediately")
    }

    @Test
    fun P1_sendWritesPendingMessageAndRowInOneTransaction() {
        val msg = outbox.send("chat-1", "hello")

        assertEquals(1, store.enqueueCalls, "message and row go in one store call")
        assertEquals(msg, store.messages.getValue(msg.id))
        assertEquals(Status.PENDING, msg.status)
        val row = store.outbox.getValue(msg.id)
        assertEquals(OutboxRow(msg.id, "chat-1", attempts = 0, nextAttemptAt = 42_000L, createdAt = 42_000L), row)
    }

    @Test
    fun P1_eachSendGetsAFreshUuidClientMsgId() {
        val a = outbox.send("chat-1", "one")
        val b = outbox.send("chat-1", "two")

        assertEquals(a.id, UUID.fromString(a.id).toString())
        assertNotEquals(a.id, b.id)
        assertEquals(2, store.outbox.size)
    }

    @Test
    fun P1_failedSaveLeavesNothingBehind() {
        store.failNextOp = true

        assertFailsWith<IllegalStateException> { outbox.send("chat-1", "hello") }

        assertTrue(store.messages.isEmpty(), "no orphan message without its outbox row")
        assertTrue(store.outbox.isEmpty())
    }

    private fun failed(): Message {
        val msg = outbox.send("chat-1", "hello")
        OutboxProcessor(store, FakeTransport(SendResult.Http(400)), clock, "dev-1") { 0.5 }.drainOnce()
        assertEquals(Status.FAILED, store.messages.getValue(msg.id).status)
        return msg
    }

    @Test
    fun P7_retryPutsAFailedMessageBackPendingAndDueNowUnderTheSameId() {
        val msg = failed()
        clock.now = 99_000L

        assertTrue(outbox.retry(msg.id))

        assertEquals(Status.PENDING, store.messages.getValue(msg.id).status)
        assertEquals(OutboxRow(msg.id, "chat-1", attempts = 0, nextAttemptAt = 99_000L, createdAt = 99_000L), store.outbox.getValue(msg.id))
        val transport = FakeTransport()
        OutboxProcessor(store, transport, clock, "dev-1") { 0.5 }.drainOnce()
        assertEquals(listOf(msg.id), transport.sentIds)
        assertEquals(Status.SENT, store.messages.getValue(msg.id).status)
    }

    @Test
    fun P7_retryOnlyAppliesToFailedMessages() {
        val pending = outbox.send("chat-1", "pending")
        val rowBefore = store.outbox.getValue(pending.id).copy(attempts = 3)
        store.outbox[pending.id] = rowBefore

        assertFalse(outbox.retry(pending.id))
        assertFalse(outbox.retry("no-such-id"))
        assertEquals(rowBefore, store.outbox.getValue(pending.id), "a pending row keeps its backoff state")
    }

    @Test
    fun P7_failedRetryLeavesTheMessageFailed() {
        val msg = failed()
        store.failNextOp = true

        assertFailsWith<IllegalStateException> { outbox.retry(msg.id) }

        assertEquals(Status.FAILED, store.messages.getValue(msg.id).status)
        assertTrue(store.outbox.isEmpty())
    }
}
