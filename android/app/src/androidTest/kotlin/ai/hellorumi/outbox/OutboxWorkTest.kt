package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Clock
import ai.hellorumi.outbox.core.Outbox
import ai.hellorumi.outbox.core.OutboxProcessor
import ai.hellorumi.outbox.core.SendResult
import ai.hellorumi.outbox.core.Transport
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDb
    private lateinit var store: RoomOutboxStore
    private val clock = Clock { 1_000_000L }
    private var reply: SendResult = SendResult.Ok(201, 1)
    private var sends = 0
    private val transport = Transport { _, _ -> sends++; reply }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).build()
        store = RoomOutboxStore(db)
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, params: WorkerParameters): ListenableWorker =
                OutboxWorker(c, params, OutboxProcessor(store, transport, clock, "dev", random = { 1.0 }), clock)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory).build(),
        )
    }

    @After fun tearDown() = db.close()

    private val wm get() = WorkManager.getInstance(context)
    private fun infos() = wm.getWorkInfosForUniqueWork(OutboxWork.NAME).get()
    private fun runAll() = infos().filter { it.state == WorkInfo.State.ENQUEUED }.forEach {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setInitialDelayMet(it.id)
        driver.setAllConstraintsMet(it.id)
    }

    @Test fun A2_workWaitsForANetworkConnection() {
        Outbox(store, clock).send("c", "hi")
        OutboxWork.enqueue(context)
        val info = infos().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(0, sends, "no send before the network is up")

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(info.id)
        assertEquals(1, sends)
        assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(info.id).get()!!.state)
    }

    @Test fun A3_rowsLeftAfterAPassScheduleTheNextRunAtTheirDueTime() {
        reply = SendResult.Http(503)
        Outbox(store, clock).send("c", "hi")
        OutboxWork.enqueue(context)
        runAll()
        val next = infos().single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1_000L, next.initialDelayMillis, "attempt 1 backs off 1 s x jitter 1.0")
        assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
    }

    @Test fun A3_emptyOutboxSchedulesNothing() {
        Outbox(store, clock).send("c", "hi")
        OutboxWork.enqueue(context)
        runAll()
        assertEquals(listOf(WorkInfo.State.SUCCEEDED), infos().map { it.state })
    }

    @Test fun A3_aSendWhileARunIsQueuedIsChainedNotDroppedOrRunAlongside() {
        OutboxWork.enqueue(context)
        OutboxWork.enqueue(context)
        assertEquals(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), infos().map { it.state }.sorted())
    }
}
