package ai.hellorumi.outbox

import android.content.SharedPreferences
import java.util.UUID

class Settings(private val prefs: SharedPreferences) {
    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL)!!
        set(v) = prefs.edit().putString("base_url", v).apply()

    var chatId: String
        get() = prefs.getString("chat_id", DEFAULT_CHAT)!!
        set(v) = prefs.edit().putString("chat_id", v).apply()

    /** Made once per install and kept: the server dedupes on (device id, client_msg_id). */
    val deviceId: String
        @Synchronized get() = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).commit() }

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.18.45:8091"
        const val DEFAULT_CHAT = "playground"
    }
}
