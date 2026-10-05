package ai.hellorumi.outbox.core

/**
 * Network port: POST the message to the server as device [deviceId]. Must not throw.
 * Any 2xx carrying a server_seq must come back as [SendResult.Ok]; [SendResult.Http] is for failures.
 */
fun interface Transport {
    fun send(deviceId: String, message: Message): SendResult
}

sealed interface SendResult {
    /** Server acked (201 new, 200 duplicate of a stored one) with its per-chat sequence number. */
    data class Ok(val httpCode: Int, val serverSeq: Long) : SendResult
    data class Http(val code: Int) : SendResult
    data class NetworkError(val cause: Throwable) : SendResult
}

/** Spec glossary: no connection, timeout, 5xx, 408, 429 are worth retrying; any other non-2xx is not. */
fun SendResult.isTransient(): Boolean = when (this) {
    is SendResult.Ok -> false
    is SendResult.NetworkError -> true
    is SendResult.Http -> code in 500..599 || code == 408 || code == 429
}
