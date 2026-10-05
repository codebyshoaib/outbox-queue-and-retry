package ai.hellorumi.outbox.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutboxProcessorTest {
    private val store = FakeStore()
    private val clock = FakeClock(1_000_000L)
    private val outbox = Outbox(store, clock)

    private fun processor(transport: Transport) = OutboxProcessor(store, transport, clock, deviceId = "dev-1")

    @Test
    fun P3_sendCarriesDeviceIdAndClientMsgId() {
        val msg = outbox.send("chat-1", "hello")
        val transport = FakeTransport()

        processor(transport).drainOnce()

        assertEquals(listOf("dev-1" to msg), transport.calls)
    }

    @Test
    fun P3_retryReusesTheSameClientMsgId() {
        val msg = outbox.send("chat-1", "hello")
        val transport = FakeTransport(SendResult.NetworkError(IOException("no route")))
        val p = processor(transport)

        p.drainOnce()
        clock.now += 60_000
        p.drainOnce()

        assertEquals(listOf(msg.id, msg.id), transport.sentIds)
    }

    @Test
    fun P4_ackMarksSentWithServerSeqAndDeletesRow() {
        val a = outbox.send("chat-1", "new")
        val b = outbox.send("chat-2", "duplicate")
        processor(FakeTransport(SendResult.Ok(201, 5), SendResult.Ok(200, 9))).drainOnce()

        assertEquals(Status.SENT to 5L, store.messages.getValue(a.id).let { it.status to it.serverSeq })
        assertEquals(Status.SENT to 9L, store.messages.getValue(b.id).let { it.status to it.serverSeq })
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun P4_crashWhileMarkingSentLeavesMessagePendingAndRowQueued() {
        val msg = outbox.send("chat-1", "hello")
        store.failNextOp = true

        assertFailsWith<IllegalStateException> { processor(FakeTransport()).drainOnce() }

        assertEquals(Status.PENDING, store.messages.getValue(msg.id).status)
        assertEquals(setOf(msg.id), store.outbox.keys, "row stays; the resend is deduped by the server")
    }

    @Test
    fun P4_lostAckThenResendOfSameIdEndsSentWithServersSeq() {
        // First POST reached the server and was stored, but the reply never came back.
        val msg = outbox.send("chat-1", "hello")
        val transport = FakeTransport(SendResult.NetworkError(IOException("connection reset")), SendResult.Ok(200, 1))
        val p = processor(transport)

        p.drainOnce()
        clock.now += 60_000
        p.drainOnce()

        assertEquals(listOf(msg.id, msg.id), transport.sentIds)
        val stored = store.messages.getValue(msg.id)
        assertEquals(Status.SENT, stored.status)
        assertEquals(1L, stored.serverSeq)
        assertTrue(store.outbox.isEmpty())
    }
}
