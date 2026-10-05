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

    override fun markSent(messageId: String, serverSeq: Long) =
        tx { m, o, crash ->
            m[messageId] = m.getValue(messageId).copy(status = Status.SENT, serverSeq = serverSeq); crash(); o.remove(messageId)
        }

    override fun reschedule(messageId: String, attempts: Int, nextAttemptAt: Long) =
        tx { _, o, _ -> o[messageId] = o.getValue(messageId).copy(attempts = attempts, nextAttemptAt = nextAttemptAt) }

    override fun markFailed(messageId: String) =
        tx { m, o, crash -> m[messageId] = m.getValue(messageId).copy(status = Status.FAILED); crash(); o.remove(messageId) }

    override fun outboxRows() = outbox.values.map { messages.getValue(it.messageId) to it }
}

/** Replies from [script] in order, then Ok with a per-chat seq. Records every call. */
class FakeTransport(vararg script: SendResult) : Transport {
    private val script = ArrayDeque(script.toList())
    private val seqs = mutableMapOf<String, Long>()
    val calls = mutableListOf<Pair<String, Message>>()
    val sentIds get() = calls.map { it.second.id }

    override fun send(deviceId: String, message: Message): SendResult {
        calls += deviceId to message
        return script.removeFirstOrNull()
            ?: SendResult.Ok(201, seqs.merge(message.chatId, 1L, Long::plus)!!)
    }
}
