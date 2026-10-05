package ai.hellorumi.outbox.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
