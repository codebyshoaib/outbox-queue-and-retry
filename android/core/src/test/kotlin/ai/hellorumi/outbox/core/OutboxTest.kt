package ai.hellorumi.outbox.core

import kotlin.test.Test
import java.util.UUID
import kotlin.test.assertEquals
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
}
