package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Message
import ai.hellorumi.outbox.core.Outbox
import ai.hellorumi.outbox.core.Status
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one screen. [kick] enqueues the outbox work after a send or retry is saved. */
@Composable
fun ChatScreen(store: RoomOutboxStore, outbox: Outbox, settings: Settings, kick: () -> Unit) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var chatId by remember { mutableStateOf(settings.chatId) }
    var text by remember { mutableStateOf("") }
    val messages by remember(chatId) { store.observe(chatId) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    // Room calls block, so they go to IO; the tap path never touches the network either way.
    fun save(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() }; kick() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(baseUrl, { baseUrl = it; settings.baseUrl = it.trim() },
            Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true)
        OutlinedTextField(chatId, { chatId = it; settings.chatId = it.trim() },
            Modifier.fillMaxWidth(), label = { Text("Chat id") }, singleLine = true)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(messages, key = { it.id }) { m -> MessageRow(m, onRetry = { save { outbox.retry(m.id) } }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text("Message") })
            Button(
                onClick = { val body = text.trim(); val chat = chatId.trim(); text = ""; save { outbox.send(chat, body) } },
                enabled = text.isNotBlank() && chatId.isNotBlank(),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageRow(m: Message, onRetry: () -> Unit) {
    val (icon, label) = when (m.status) {
        Status.PENDING -> "🕓" to "pending"
        Status.SENT -> "✓" to "sent"
        Status.FAILED -> "⚠" to "failed, tap to retry"
    }
    val tap = if (m.status == Status.FAILED) Modifier.clickable(onClickLabel = "retry", onClick = onRetry) else Modifier
    Row(tap.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, Modifier.semantics { contentDescription = label })
        Text(m.body, Modifier.weight(1f))
        m.serverSeq?.let { Text("#$it") }
    }
}
