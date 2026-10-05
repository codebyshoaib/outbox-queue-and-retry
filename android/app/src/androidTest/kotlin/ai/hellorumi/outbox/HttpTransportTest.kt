package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Message
import ai.hellorumi.outbox.core.SendResult
import ai.hellorumi.outbox.core.Status
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the real proxy + server, so each test uses its own chat. */
@RunWith(AndroidJUnit4::class)
class HttpTransportTest {
    private val transport = HttpTransport(Lab.baseUrl)
    private val device = "test-" + UUID.randomUUID()
    private val chat = "transport-" + UUID.randomUUID()
    private fun msg(body: String = "hi") = Message(UUID.randomUUID().toString(), chat, body, 0, Status.PENDING)

    @Before @After fun clearChaos() = Lab.chaos("drop_request", 0)

    @Test fun transport_201IsOkWithServerSeq() {
        assertEquals(SendResult.Ok(201, 1), transport.send(device, msg()))
        assertEquals(SendResult.Ok(201, 2), transport.send(device, msg()))
    }

    @Test fun transport_duplicateIs200WithTheSameSeq() {
        val m = msg()
        transport.send(device, m)
        assertEquals(SendResult.Ok(200, 1), transport.send(device, m))
    }

    @Test fun transport_400IsHttp400() {
        assertEquals(SendResult.Http(400), transport.send(device, msg(body = "")))
    }

    @Test fun transport_dropReplyIsNetworkErrorButTheServerHasTheRow() {
        Lab.chaos("drop_reply", 1)
        val m = msg()
        assertIs<SendResult.NetworkError>(transport.send(device, m))
        assertEquals(listOf(m.id), Lab.serverMessages(chat).map { it.getString("client_msg_id") })
    }

    @Test fun transport_dropRequestIsNetworkErrorAndTheServerHasNothing() {
        Lab.chaos("drop_request", 1)
        assertIs<SendResult.NetworkError>(transport.send(device, msg()))
        assertEquals(emptyList(), Lab.serverMessages(chat))
    }

    @Test fun transport_unreachableHostIsNetworkError() {
        // TEST-NET-1 (RFC 5737): never routed, so this is a connect timeout.
        assertIs<SendResult.NetworkError>(HttpTransport("http://192.0.2.1:9", timeoutMs = 500).send(device, msg()))
    }
}
