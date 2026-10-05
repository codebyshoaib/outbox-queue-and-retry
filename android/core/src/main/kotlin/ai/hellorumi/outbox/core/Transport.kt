package ai.hellorumi.outbox.core

/** Network port: POST the message to the server as device [deviceId]. Must not throw. */
fun interface Transport {
    fun send(deviceId: String, message: Message): SendResult
}

sealed interface SendResult {
    /** Server acked (201 new, 200 duplicate of a stored one) with its per-chat sequence number. */
    data class Ok(val httpCode: Int, val serverSeq: Long) : SendResult
    data class Http(val code: Int) : SendResult
    data class NetworkError(val cause: Throwable) : SendResult
}
