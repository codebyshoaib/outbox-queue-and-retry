package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Clock
import ai.hellorumi.outbox.core.Outbox
import ai.hellorumi.outbox.core.OutboxProcessor
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/** Wiring. WorkManager is initialised on demand from [workManagerConfiguration] (see manifest). */
class OutboxApp : Application(), Configuration.Provider {
    private val clock = Clock(System::currentTimeMillis)
    val settings by lazy { Settings(getSharedPreferences("outbox", MODE_PRIVATE)) }
    val store by lazy { RoomOutboxStore(AppDb.open(this)) }
    val outbox by lazy { Outbox(store, clock) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, params: WorkerParameters): ListenableWorker? =
                if (name != OutboxWorker::class.java.name) null
                // The URL is read per pass, so a settings change applies from the next run.
                else OutboxWorker(c, params, OutboxProcessor(store, HttpTransport(settings.baseUrl), clock, settings.deviceId), clock)
        }).build()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OutboxApp
        OutboxWork.enqueue(this) // A3: on app start, in case rows were left by a kill or reboot.
        setContent {
            MaterialTheme { Surface { ChatScreen(app.store, app.outbox, app.settings) { OutboxWork.enqueue(this) } } }
        }
    }
}
