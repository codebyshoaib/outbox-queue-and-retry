package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Clock
import ai.hellorumi.outbox.core.Outbox
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDb
    private lateinit var store: RoomOutboxStore
    private lateinit var outbox: Outbox
    private val settings = Settings(context.getSharedPreferences("test-" + UUID.randomUUID(), 0))
    private var kicks = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).build()
        store = RoomOutboxStore(db)
        outbox = Outbox(store, Clock { 1_000L })
        compose.setContent { ChatScreen(store, outbox, settings, kick = { kicks++ }) }
    }

    @After fun tearDown() = db.close()

    @Test fun A5_sendShowsAPendingRowAndKicksTheWorker() {
        compose.onNodeWithText("Message").performTextInput("hello")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(5_000) { kicks == 1 }
        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithContentDescription("pending").assertIsDisplayed()
        assertEquals("playground", store.outboxRows().single().first.chatId)
    }

    @Test fun A5_sentRowShowsItsServerSeq() {
        val m = outbox.send("playground", "hi")
        store.markSent(m.id, 4)
        compose.onNodeWithContentDescription("sent").assertIsDisplayed()
        compose.onNodeWithText("#4").assertIsDisplayed()
    }

    @Test fun A5_tappingAFailedRowRetriesIt() {
        val m = outbox.send("playground", "oops")
        store.markFailed(m.id)
        compose.onNodeWithContentDescription("failed, tap to retry").performClick()
        compose.waitUntil(5_000) { kicks == 1 }
        compose.onNodeWithContentDescription("pending").assertIsDisplayed()
        assertEquals(m.id, store.outboxRows().single().first.id)
    }

    @Test fun A5_settingsChangeTheChatAndServerUrl() {
        assertEquals("http://192.168.18.45:8091", settings.baseUrl)
        compose.onNodeWithText("http://192.168.18.45:8091").performTextReplacement("http://10.0.0.2:8091")
        compose.onNodeWithText("playground").performTextReplacement("other")
        compose.waitForIdle()
        assertEquals("http://10.0.0.2:8091", settings.baseUrl)
        assertEquals("other", settings.chatId)
    }

    @Test fun A5_deviceIdIsMadeOnceAndKept() {
        val id = settings.deviceId
        assertEquals(id, settings.deviceId)
        assertNotEquals(id, Settings(context.getSharedPreferences("test-" + UUID.randomUUID(), 0)).deviceId)
        UUID.fromString(id)
    }
}
