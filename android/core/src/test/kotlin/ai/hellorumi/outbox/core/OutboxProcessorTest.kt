package ai.hellorumi.outbox.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxProcessorTest {
    private val store = FakeStore()
    private val clock = FakeClock(1_000_000L)
    private val outbox = Outbox(store, clock)

    private fun processor(transport: Transport) =
        OutboxProcessor(store, transport, clock, deviceId = "dev-1", random = { 0.5 }) // jitter 0.75

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

    @Test
    fun P5_transientFailureBacksOffWithJitter() {
        val msg = outbox.send("chat-1", "hello")
        val start = clock.now
        val p = processor(FakeTransport(SendResult.NetworkError(IOException("timeout")), SendResult.Http(503)))

        assertEquals(start + 750, p.drainOnce(), "1 s x 0.75 jitter")
        assertEquals(OutboxRow(msg.id, "chat-1", 1, start + 750, start), store.outbox.getValue(msg.id))

        clock.now = start + 750
        assertEquals(start + 750 + 1_500, p.drainOnce(), "2 s x 0.75 jitter")
        assertEquals(2, store.outbox.getValue(msg.id).attempts)
        assertEquals(Status.PENDING, store.messages.getValue(msg.id).status)
    }

    @Test
    fun P5_rowIsNotResentBeforeItsBackoffEnds() {
        outbox.send("chat-1", "hello")
        val transport = FakeTransport(SendResult.Http(500))
        val p = processor(transport)

        val next = p.drainOnce()!!
        clock.now = next - 1
        p.drainOnce()

        assertEquals(1, transport.calls.size)
    }

    @Test
    fun P5_timeoutsServerErrorsAnd408And429AreTransient() {
        val results = listOf(500, 502, 503, 599, 408, 429).map { SendResult.Http(it) } +
            SendResult.NetworkError(IOException("no connection"))
        for (result in results) {
            val msg = outbox.send("chat-$result", "hello")
            processor(FakeTransport(result)).drainOnce()
            assertEquals(1, store.outbox.getValue(msg.id).attempts, "$result")
            assertEquals(Status.PENDING, store.messages.getValue(msg.id).status, "$result")
        }
    }

    @Test
    fun P6_permanentFailureMarksFailedAndDropsRow() {
        val msg = outbox.send("chat-1", "hello")

        val next = processor(FakeTransport(SendResult.Http(400))).drainOnce()

        assertEquals(Status.FAILED, store.messages.getValue(msg.id).status)
        assertTrue(store.outbox.isEmpty())
        assertNull(next, "nothing left to wake up for")
    }

    @Test
    fun P6_failedMessageIsNeverRetriedByItself() {
        outbox.send("chat-1", "hello")
        val transport = FakeTransport(SendResult.Http(422))
        val p = processor(transport)

        p.drainOnce()
        clock.now += 24 * 3_600_000L
        p.drainOnce()

        assertEquals(1, transport.calls.size)
    }

    @Test
    fun P6_anyOtherNon2xxStatusIsPermanent() {
        for (code in listOf(100, 301, 304, 400, 401, 403, 404, 409, 413, 418, 422, 451)) {
            val msg = outbox.send("chat-$code", "hello")
            processor(FakeTransport(SendResult.Http(code))).drainOnce()
            assertEquals(Status.FAILED, store.messages.getValue(msg.id).status, "HTTP $code")
        }
    }

    @Test
    fun P6_permanentFailureDoesNotBlockTheNextMessageInTheChat() {
        val bad = outbox.send("chat-1", "rejected")
        val good = outbox.send("chat-1", "fine")

        processor(FakeTransport(SendResult.Http(400))).drainOnce()

        assertEquals(Status.FAILED, store.messages.getValue(bad.id).status)
        assertEquals(Status.SENT, store.messages.getValue(good.id).status)
    }

    @Test
    fun P2_laterMessageWaitsWhileTheEarlierOneBacksOff() {
        val first = outbox.send("chat-1", "first")
        val second = outbox.send("chat-1", "second")
        val transport = FakeTransport(SendResult.Http(503))
        val p = processor(transport)

        val wake = p.drainOnce()!!
        assertEquals(listOf(first.id), transport.sentIds, "second must not overtake first")

        clock.now = wake
        p.drainOnce()
        assertEquals(listOf(first.id, first.id, second.id), transport.sentIds)
        assertEquals(1L, store.messages.getValue(first.id).serverSeq)
        assertEquals(2L, store.messages.getValue(second.id).serverSeq)
    }

    @Test
    fun P2_aBlockedChatDoesNotBlockOtherChats() {
        outbox.send("chat-a", "stuck")
        val other = outbox.send("chat-b", "flows")

        processor(FakeTransport(SendResult.NetworkError(IOException("timeout")))).drainOnce()

        assertEquals(Status.SENT, store.messages.getValue(other.id).status)
    }

    @Test
    fun P2_sendsDueRowsOldestFirst() {
        val a1 = outbox.send("chat-a", "a1")
        clock.now += 1
        val b1 = outbox.send("chat-b", "b1")
        clock.now += 1
        val a2 = outbox.send("chat-a", "a2")
        val transport = FakeTransport()

        processor(transport).drainOnce()

        assertEquals(listOf(a1.id, b1.id, a2.id), transport.sentIds)
    }

    @Test
    fun P2_wakeUpTimeIsTheEarliestChatHeadNotARowQueuedBehindIt() {
        outbox.send("chat-1", "first")
        outbox.send("chat-1", "second") // due now, but cannot go until "first" is acked
        val start = clock.now

        val wake = processor(FakeTransport(SendResult.Http(503))).drainOnce()

        assertEquals(start + 750, wake, "waking at the blocked row's time would busy-loop")
    }
}
