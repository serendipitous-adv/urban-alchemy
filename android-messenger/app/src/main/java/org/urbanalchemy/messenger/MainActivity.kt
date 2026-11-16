package org.urbanalchemy.messenger

import android.os.Bundle
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.Python
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
            var contacts by remember { mutableStateOf(store.listContacts()) }
            var selectedId by remember { mutableStateOf<Long?>(null) }
            var messages by remember { mutableStateOf(emptyList<LocalMessage>()) }
            var showContacts by remember { mutableStateOf(false) }
            var runtimeStatus by remember { mutableStateOf("Not checked") }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val selected = conversations.firstOrNull { it.id == selectedId }
                    when {
                        selected != null -> ChatScreen(
                            conversation = selected,
                            messages = messages,
                            onBack = { selectedId = null },
                            onSend = { text ->
                                store.addQueuedMessage(selected.id, text)
                                messages = store.listMessages(selected.id)
                                conversations = store.listConversations()
                            }
                        )
                        showContacts -> ContactsScreen(
                            contacts = contacts,
                            onBack = { showContacts = false },
                            onAddContact = { name, address ->
                                store.addContact(name, address)
                                contacts = store.listContacts()
                            },
                            onStartConversation = { contactId ->
                                val id = store.createConversationForContact(contactId)
                                conversations = store.listConversations()
                                messages = store.listMessages(id)
                                selectedId = id
                                showContacts = false
                            }
                        )
                        else -> MessengerHome(
                            fingerprint = identity.fingerprint,
                            conversations = conversations,
                            contactCount = contacts.size,
                            runtimeStatus = runtimeStatus,
                            onCheckRuntime = {
                                runtimeStatus = "Checking embedded Python modules..."
                                lifecycleScope.launch {
                                    runtimeStatus = try {
                                        withContext(Dispatchers.IO) {
                                            if (!Python.isStarted()) {
                                                Python.start(AndroidPlatform(applicationContext))
                                            }
                                            Python.getInstance()
                                                .getModule("transport_probe")
                                                .callAttr("check_runtime")
                                                .toString()
                                        }
                                    } catch (error: Exception) {
                                        "FAILED: ${error.javaClass.simpleName}: ${error.message}"
                                    }
                                }
                            },
                            onOpen = { id ->
                                selectedId = id
                                messages = store.listMessages(id)
                            },
                            onCreateConversation = { title ->
                                store.createConversation(title)
                                conversations = store.listConversations()
                            },
                            onContacts = { showContacts = true }
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
    contactCount: Int,
    runtimeStatus: String,
    onCheckRuntime: () -> Unit,
    onOpen: (Long) -> Unit,
    onCreateConversation: (String) -> Unit,
    onContacts: () -> Unit
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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transport runtime diagnostic", style = MaterialTheme.typography.titleSmall)
                Text(runtimeStatus, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onCheckRuntime) { Text("Check embedded runtime") }
                Text("Import test only. Does not connect, announce, or send messages.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onContacts) { Text("Contacts ($contactCount)") }
        }
        if (conversations.isEmpty()) {
            Text("No conversations yet. Create one below or start from a contact.")
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
                                if (conversation.recipientAddress != null) {
                                    Text("To: ${conversation.recipientAddress}", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text("Local-only conversation", style = MaterialTheme.typography.labelSmall)
                                }
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
                label = { Text("New local conversation") },
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
private fun ContactsScreen(
    contacts: List<Contact>,
    onBack: () -> Unit,
    onAddContact: (String, String) -> Unit,
    onStartConversation: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Conversations") }
        Text("Contacts", style = MaterialTheme.typography.headlineMedium)
        Text("Add a contact using their 32-character LXMF destination. Contacts are stored only on this device.")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Contact name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; error = null },
            label = { Text("LXMF destination (32 hex characters)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                try {
                    onAddContact(name, address)
                    name = ""
                    address = ""
                    error = null
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not add contact."
                }
            },
            enabled = name.isNotBlank() && address.isNotBlank()
        ) { Text("Save contact") }

        Text("Saved contacts", style = MaterialTheme.typography.titleLarge)
        if (contacts.isEmpty()) {
            Text("No contacts yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts, key = { it.id }) { contact ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(contact.destination, style = MaterialTheme.typography.bodySmall)
                            Text("Unverified · Address saved locally", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { onStartConversation(contact.id) }) {
                                Text("Start conversation")
                            }
                        }
                    }
                }
            }
        }
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
                Text(
                    conversation.recipientAddress?.let { "Recipient: $it · unverified" }
                        ?: "Private local conversation",
                    style = MaterialTheme.typography.bodySmall
                )
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
        Text(
            if (conversation.recipientAddress == null)
                "Queued on this device · Local-only"
            else
                "Queued on this device · Recipient saved, not yet sent",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
