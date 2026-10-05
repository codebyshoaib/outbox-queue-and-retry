package ai.hellorumi.outbox.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
