package ai.hellorumi.outbox.core

class OutboxProcessor(
    private val store: OutboxStore,
    private val transport: Transport,
    private val clock: Clock,
    private val deviceId: String,
) {
    fun drainOnce(): Long? {
        for ((message, _) in store.outboxRows()) {
            val result = transport.send(deviceId, message)
            if (result is SendResult.Ok) store.markSent(message.id, result.serverSeq)
        }
        return null
    }
}
