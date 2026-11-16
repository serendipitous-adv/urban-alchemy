package org.urbanalchemy.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class Conversation(val id: Long, val title: String, val preview: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalStore(this)
        val identity = DeviceIdentity.getOrCreate()
        setContent {
            var conversations by remember { mutableStateOf(store.listConversations()) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MessengerHome(
                        fingerprint = identity.fingerprint,
                        conversations = conversations,
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

@Composable
private fun MessengerHome(
    fingerprint: String,
    conversations: List<Conversation>,
    onCreateConversation: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Urban Alchemy", style = MaterialTheme.typography.headlineMedium)
        Text("Messenger · Offline-first", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Your local identity", style = MaterialTheme.typography.titleSmall)
                Text(fingerprint, style = MaterialTheme.typography.bodySmall)
                Text("Private key stays on this device.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Conversations", style = MaterialTheme.typography.titleLarge)
        if (conversations.isEmpty()) {
            Text("No conversations yet. Create one to start building your local message history.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                            Text(conversation.preview.ifBlank { "No messages yet" })
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }) {
                Text("Create")
            }
        }
        Text("Transport: local only · No network connection required", style = MaterialTheme.typography.bodySmall)
    }
}
