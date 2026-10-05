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
     * A person acted (send, retry tap, app start): run a pass now. REPLACE cancels a run that is only
     * waiting out a back-off delay (A6). A run that is mid-pass keeps going on its own thread (Worker is
     * blocking, cancel only flags it) and the new run waits at [OutboxWorker]'s lock, so two passes
     * never overlap. The lock, not the work queue, is the guarantee.
     */
    fun kick(context: Context) = schedule(context, 0, ExistingWorkPolicy.REPLACE)

    /**
     * The worker re-queuing itself after a pass. APPEND_OR_REPLACE: runs after the current run, never
     * dropped while that run is in progress (KEEP would drop it); "_OR_REPLACE" starts a fresh chain if
     * the previous run failed or was cancelled.
     */
    fun enqueue(context: Context, delayMs: Long = 0) = schedule(context, delayMs, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun schedule(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
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
        val next = synchronized(LOCK) { processor.drainOnce() }
        if (next != null) OutboxWork.enqueue(applicationContext, (next - clock.now()).coerceAtLeast(0))
        return Result.success()
    }

    // ponytail: in-process lock. Holds because the app runs in one process and this Worker is
    // blocking. A multi-process WorkManager or a CoroutineWorker (cancel would stop it mid-pass)
    // needs a cross-process guard instead, e.g. a "draining" row claimed in the DB.
    private companion object { val LOCK = Any() }
}
