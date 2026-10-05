package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Message
import ai.hellorumi.outbox.core.SendResult
import ai.hellorumi.outbox.core.Transport
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

/** POST {baseUrl}/messages. Never throws: every outcome is a [SendResult]. */
class HttpTransport(private val baseUrl: String, private val timeoutMs: Int = 10_000) : Transport {
    override fun send(deviceId: String, message: Message): SendResult {
        val body = JSONObject()
            .put("client_msg_id", message.id)
            .put("chat_id", message.chatId)
            .put("body", message.body)
            .toString().toByteArray()
        var c: HttpURLConnection? = null
        return try {
            c = URL(baseUrl.trimEnd('/') + "/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Device-Id", deviceId)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) return SendResult.Http(code)
            val seq = JSONObject(c.inputStream.bufferedReader().readText()).getLong("server_seq")
            SendResult.Ok(code, seq)
        } catch (e: IOException) {
            SendResult.NetworkError(e)
        } catch (e: JSONException) {
            // A 2xx without a server_seq is a broken server, not a network blip: do not loop on it.
            SendResult.Http(c?.responseCode ?: 0)
        } finally {
            c?.disconnect()
        }
    }
}
