package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Clock
import ai.hellorumi.outbox.core.Outbox
import ai.hellorumi.outbox.core.OutboxProcessor
import ai.hellorumi.outbox.core.Status
import ai.hellorumi.outbox.core.Transport
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The automated chaos tests: real Room store, real HTTP transport, real proxy and Django over Wi-Fi.
 * Only time is fake, so backoff gaps are exact and no test sleeps.
 */
@RunWith(AndroidJUnit4::class)
class AcceptanceTest {
    private lateinit var db: AppDb
    private lateinit var store: RoomOutboxStore
    private var now = 1_000_000L
    private val clock = Clock { now }
    private val chat = "accept-" + UUID.randomUUID()
    private val requests = mutableListOf<String>()
    private val http = HttpTransport(Lab.baseUrl)
    private val transport = Transport { d, m -> requests += m.id; http.send(d, m) }
    private lateinit var outbox: Outbox
    private lateinit var processor: OutboxProcessor

    @Before fun setUp() {
        Lab.chaos("drop_request", 0)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).build()
        store = RoomOutboxStore(db)
        outbox = Outbox(store, clock)
        processor = OutboxProcessor(store, transport, clock, "accept-" + UUID.randomUUID(), random = { 1.0 })
    }

    @After fun tearDown() {
        Lab.chaos("drop_request", 0)
        db.close()
    }

    private fun message(id: String) = db.dao().message(id)!!.toMessage()

    /** Runs passes, jumping the clock to each wake-up time, until the outbox is empty. Returns the gaps. */
    private fun drainAll(): List<Long> {
        val gaps = mutableListOf<Long>()
        while (true) {
            val next = processor.drainOnce() ?: return gaps
            gaps += next - now
            now = next
            check(gaps.size < 20) { "outbox never drained" }
        }
    }

    @Test fun T1_lostAckDeliversExactlyOnce() {
        Lab.chaos("drop_reply", 1)
        val m = outbox.send(chat, "lost ack")
        drainAll()
        assertEquals(listOf(m.id, m.id), requests, "resent once under the same id")
        val rows = Lab.serverMessages(chat)
        assertEquals(listOf(m.id), rows.map { it.getString("client_msg_id") }, "server has exactly 1 row")
        assertEquals(Status.SENT to 1L, message(m.id).let { it.status to it.serverSeq })
    }

    @Test fun T4_permanentErrorFailsWithoutRetryingUntilTapped() {
        Lab.chaos("status:400", 1)
        val m = outbox.send(chat, "rejected once")
        drainAll()
        assertEquals(Status.FAILED, message(m.id).status)
        now += 3_600_000
        assertEquals(null, processor.drainOnce())
        assertEquals(listOf(m.id), requests, "no further request for a failed message")
        assertEquals(emptyList(), Lab.serverMessages(chat))

        assertTrue(outbox.retry(m.id))
        drainAll()
        assertEquals(Status.SENT to 1L, message(m.id).let { it.status to it.serverSeq })
        assertEquals(listOf(m.id), Lab.serverMessages(chat).map { it.getString("client_msg_id") })
    }

    @Test fun T3a_serverErrorsThenRecoveryDeliverAllInOrderWithGrowingGaps() {
        Lab.chaos("status:503", 3)
        val sent = listOf("one", "two", "three").map { outbox.send(chat, it) }
        val gaps = drainAll()
        assertEquals(listOf(1_000L, 2_000L, 4_000L), gaps, "1 s, 2 s, 4 s with jitter pinned at 1.0")
        assertEquals(List(3) { sent[0].id } + sent.map { it.id }, requests, "the head blocks its chat while it backs off")
        val rows = Lab.serverMessages(chat)
        assertEquals(listOf("one", "two", "three"), rows.map { it.getString("body") })
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.getLong("server_seq") })
        assertEquals(listOf(1L, 2L, 3L), sent.map { message(it.id).serverSeq })
    }
}
