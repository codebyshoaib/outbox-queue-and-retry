package ai.hellorumi.outbox.core

class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun now() = now
}

/**
 * In-memory [OutboxStore] that behaves like a DB transaction: every op works on a copy and only
 * swaps it in at the end, so [failNextOp] (thrown after the first write) leaves no partial state.
 */
class FakeStore : OutboxStore {
    val messages = linkedMapOf<String, Message>()
    val outbox = linkedMapOf<String, OutboxRow>() // insertion order == age order
    var failNextOp = false
    var enqueueCalls = 0

    private fun tx(block: (MutableMap<String, Message>, MutableMap<String, OutboxRow>, () -> Unit) -> Unit) {
        val m = LinkedHashMap(messages)
        val o = LinkedHashMap(outbox)
        val crashPoint = { if (failNextOp) { failNextOp = false; throw IllegalStateException("disk full") } }
        block(m, o, crashPoint)
        messages.clear(); messages.putAll(m)
        outbox.clear(); outbox.putAll(o)
    }

    override fun enqueue(message: Message, row: OutboxRow) {
        enqueueCalls++
        tx { m, o, crash -> m[message.id] = message; crash(); o[row.messageId] = row }
    }
}
