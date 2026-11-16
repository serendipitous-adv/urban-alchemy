package org.urbanalchemy.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalStore(this)
        val identity = DeviceIdentity.getOrCreate()
        setContent {
            var conversations by remember { mutableStateOf(store.listConversations()) }
            var selectedId by remember { mutableStateOf<Long?>(null) }
            var messages by remember { mutableStateOf(emptyList<LocalMessage>()) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val selected = conversations.firstOrNull { it.id == selectedId }
                    if (selected != null) {
                        ChatScreen(
                            conversation = selected,
                            messages = messages,
                            onBack = { selectedId = null },
                            onSend = { text ->
                                store.addQueuedMessage(selected.id, text)
                                messages = store.listMessages(selected.id)
                                conversations = store.listConversations()
                            }
                        )
                    } else {
                        MessengerHome(
                            fingerprint = identity.fingerprint,
                            conversations = conversations,
                            onOpen = { id ->
                                selectedId = id
                                messages = store.listMessages(id)
                            },
                            onCreateConversation = { title ->
                                store.createConversation(title)
                                conversations = store.listConversations()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessengerHome(
    fingerprint: String,
    conversations: List<Conversation>,
    onOpen: (Long) -> Unit,
    onCreateConversation: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Urban Alchemy", style = MaterialTheme.typography.headlineMedium)
        Text("Messenger · Offline-first", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Your local identity", style = MaterialTheme.typography.titleSmall)
                Text(fingerprint, style = MaterialTheme.typography.bodySmall)
                Text("Private identity key stays on this device.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Conversations", style = MaterialTheme.typography.titleLarge)
        if (conversations.isEmpty()) {
            Text("No conversations yet. Create one below.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { onOpen(conversation.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                                Text(conversation.preview, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New conversation") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = {
                val title = newName.trim()
                if (title.isNotEmpty()) {
                    onCreateConversation(title)
                    newName = ""
                }
            }) { Text("Create") }
        }
        Text("Local storage only · No internet or server required", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChatScreen(
    conversation: Conversation,
    messages: List<LocalMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var draft by remember(conversation.id) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("‹ Conversations") }
            Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleLarge)
                Text("Private local conversation", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (messages.isEmpty()) {
            Text("No messages yet. Send a note to create your first encrypted local message.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(message.text)
                            Text(
                                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt))} · ${message.status.lowercase()}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(0.01f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") }
            )
            Button(onClick = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    draft = ""
                }
            }, enabled = draft.isNotBlank()) { Text("Send") }
        }
        Text("Queued on this device · Not sent to a recipient", style = MaterialTheme.typography.bodySmall)
    }
}
