package ai.hellorumi.outbox

import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** The real chaos proxy and Django server, reached over Wi-Fi. Override with -P...baseUrl=. */
object Lab {
    val baseUrl: String = InstrumentationRegistry.getArguments().getString("baseUrl") ?: "http://192.168.18.45:8091"

    /** POST /_chaos. count 0 clears. */
    fun chaos(mode: String, count: Int) {
        http("POST", "/_chaos", JSONObject().put("mode", mode).put("count", count).toString())
    }

    /** GET /messages?chat_id= through the proxy (call it only after chaos is used up). */
    fun serverMessages(chatId: String): List<JSONObject> {
        val arr = JSONArray(http("GET", "/messages?chat_id=$chatId", null))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun http(method: String, path: String, body: String?): String {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            check(c.responseCode == 200) { "$method $path -> ${c.responseCode}" }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
