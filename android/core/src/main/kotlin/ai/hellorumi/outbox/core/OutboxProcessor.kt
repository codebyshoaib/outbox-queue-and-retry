package ai.hellorumi.outbox.core

class OutboxProcessor(
    private val store: OutboxStore,
    private val transport: Transport,
    private val clock: Clock,
    private val deviceId: String,
) {
    fun drainOnce(): Long? {
        for ((message, _) in store.outboxRows()) transport.send(deviceId, message)
        return null
    }
}
