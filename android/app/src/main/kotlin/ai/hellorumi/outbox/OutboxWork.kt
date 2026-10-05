package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Clock
import ai.hellorumi.outbox.core.OutboxProcessor
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object OutboxWork {
    const val NAME = "outbox-drain"

    /**
     * APPEND_OR_REPLACE: a new request runs after the current one, never alongside it (drainOnce is
     * not reentrant), and is never dropped, so a send that lands mid-pass still gets a pass of its
     * own. KEEP would drop it (and the worker's own reschedule) while a run is in progress; REPLACE
     * would cancel a pass mid-send and start a second one while the first thread is still running.
     * "_OR_REPLACE" starts a fresh chain if the previous run failed or was cancelled.
     *
     * ponytail: a send behind a run that is waiting out a backoff delay waits too (at most 60 s),
     * even for another chat, and each send adds one cheap pass to the chain. Fix if that latency
     * matters: cancel only an ENQUEUED (not RUNNING) delayed run before appending.
     */
    fun enqueue(context: Context, delayMs: Long = 0) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** One drain pass. Built by the app's WorkerFactory, which supplies the processor. */
class OutboxWorker(
    context: Context,
    params: WorkerParameters,
    private val processor: OutboxProcessor,
    private val clock: Clock,
) : Worker(context, params) {
    override fun doWork(): Result {
        val next = processor.drainOnce()
        if (next != null) OutboxWork.enqueue(applicationContext, (next - clock.now()).coerceAtLeast(0))
        return Result.success()
    }
}
