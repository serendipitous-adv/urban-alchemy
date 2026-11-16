package org.urbanalchemy.messenger

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import org.json.JSONObject
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var reticulumMulticastLock: WifiManager.MulticastLock? = null

    private fun acquireReticulumMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = reticulumMulticastLock ?: wifi.createMulticastLock("UrbanAlchemyReticulum").also {
            it.setReferenceCounted(false)
            reticulumMulticastLock = it
        }
        if (!lock.isHeld) lock.acquire()
    }

    override fun onDestroy() {
        try {
            reticulumMulticastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        reticulumMulticastLock = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalStore(this)
        val identity = DeviceIdentity.getOrCreate()
        val uaIdentity = store.getOrCreateUaIdentity()
        setContent {
            var conversations by remember { mutableStateOf(store.listConversations()) }
            var contacts by remember { mutableStateOf(store.listContacts()) }
            var selectedId by remember { mutableStateOf<Long?>(null) }
            var messages by remember { mutableStateOf(emptyList<LocalMessage>()) }
            var showContacts by remember { mutableStateOf(false) }
            var runtimeStatus by remember { mutableStateOf("Not checked") }
            var transportStatus by remember { mutableStateOf("Not started") }
            var lxmfAddress by remember { mutableStateOf<String?>(null) }
            var transportStarted by remember { mutableStateOf(false) }
            var isRefreshing by remember { mutableStateOf(false) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val selected = conversations.firstOrNull { it.id == selectedId }
                    when {
                        selected != null -> ChatScreen(
                            conversation = selected,
                            messages = messages,
                            onBack = { selectedId = null },
                            onSend = { text ->
                                if (selected.recipientAddress?.let { Regex("^[0-9a-f]{32}$").matches(it) } == true) {
                                    store.addQueuedMessage(selected.id, text)
                                } else {
                                    store.addLocalMessage(selected.id, text)
                                }
                                messages = store.listMessages(selected.id)
                                conversations = store.listConversations()
                            }
                        )
                        showContacts -> ContactsScreen(
                            contacts = contacts,
                            onShareInvite = {
                                try {
                                    acquireReticulumMulticastLock()
                                    transportStatus = "Preparing your LXMF contact invite..."
                                    lifecycleScope.launch {
                                        try {
                                            val invite = withContext(Dispatchers.IO) {
                                                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                                                val module = Python.getInstance().getModule("transport_probe")
                                                val runtimeJson = module.callAttr(
                                                    "start_runtime",
                                                    filesDir.resolve("reticulum").absolutePath,
                                                    "Urban Alchemy"
                                                ).toString()
                                                val runtimeInfo = JSONObject(runtimeJson)
                                                store.bindReticulumIdentity(
                                                    runtimeInfo.getString("reticulum_identity_hash"),
                                                    runtimeInfo.getString("reticulum_public_key"),
                                                    runtimeInfo.getString("destination")
                                                )
                                                module.callAttr(
                                                    "create_contact_invite",
                                                    "Urban Alchemy",
                                                    uaIdentity.playerId
                                                ).toString()
                                            }
                                            transportStarted = true
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, invite)
                                            }
                                            startActivity(Intent.createChooser(sendIntent, "Share your Urban Alchemy contact invite"))
                                            transportStatus = "Your public LXMF contact invite is ready to share."
                                        } catch (error: Exception) {
                                            transportStatus = "Could not create invite: ${error.message ?: error.javaClass.simpleName}"
                                        }
                                    }
                                } catch (error: Exception) {
                                    transportStatus = "Could not prepare Reticulum: ${error.message ?: error.javaClass.simpleName}"
                                }
                            },
                            onBack = { showContacts = false },
                            onValidateInvite = { rawInvite, complete ->
                                lifecycleScope.launch {
                                    try {
                                        val validated = withContext(Dispatchers.IO) {
                                            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                                            Python.getInstance().getModule("transport_probe")
                                                .callAttr("validate_contact_invite", rawInvite)
                                                .toString()
                                        }
                                        complete(validated, null)
                                    } catch (error: Exception) {
                                        complete(null, error.message ?: "This invite could not be verified.")
                                    }
                                }
                            },
                            onSaveVerifiedInvite = { inviteJson ->
                                val invite = JSONObject(inviteJson)
                                store.addVerifiedContact(
                                    invite.getString("display_name"),
                                    invite.getString("lxmf_destination"),
                                    invite.getString("reticulum_identity_hash"),
                                    invite.getString("reticulum_public_key"),
                                    invite.optString("player_id").takeIf { it.isNotBlank() && it != "null" }
                                )
                                contacts = store.listContacts()
                            },
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
                            playerId = uaIdentity.playerId,
                            nodeId = uaIdentity.nodeId,
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                isRefreshing = true
                                lifecycleScope.launch {
                                    try {
                                        val snapshot = withContext(Dispatchers.IO) {
                                            store.listConversations() to store.listContacts()
                                        }
                                        conversations = snapshot.first
                                        contacts = snapshot.second
                                    } catch (error: Exception) {
                                        runtimeStatus = "Refresh failed: ${error.message ?: error.javaClass.simpleName}"
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
                            },
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
                            transportStatus = transportStatus,
                            lxmfAddress = lxmfAddress,
                            transportStarted = transportStarted,
                            onStartTransport = {
                                try {
                                    acquireReticulumMulticastLock()
                                    transportStatus = "Starting Reticulum and loading the persistent LXMF identity..."
                                    lifecycleScope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                if (!Python.isStarted()) {
                                                    Python.start(AndroidPlatform(applicationContext))
                                                }
                                                Python.getInstance()
                                                    .getModule("transport_probe")
                                                    .callAttr(
                                                        "start_runtime",
                                                        filesDir.resolve("reticulum").absolutePath,
                                                        "Urban Alchemy"
                                                    ).toString()
                                            }
                                            val info = JSONObject(result)
                                            val destination = info.getString("destination")
                                            store.bindReticulumIdentity(
                                                info.getString("reticulum_identity_hash"),
                                                info.getString("reticulum_public_key"),
                                                destination
                                            )
                                            lxmfAddress = destination
                                            transportStarted = true
                                            transportStatus = "Reticulum started. LXMF identity is saved on this device; no peer has been confirmed yet."
                                        } catch (error: Exception) {
                                            transportStatus = "FAILED: ${error.javaClass.simpleName}: ${error.message}"
                                        }
                                    }
                                } catch (error: Exception) {
                                    transportStatus = "FAILED: ${error.javaClass.simpleName}: ${error.message}"
                                }
                            },
                            onAnnounce = {
                                transportStatus = "Submitting LXMF announce..."
                                lifecycleScope.launch {
                                    transportStatus = try {
                                        withContext(Dispatchers.IO) {
                                            Python.getInstance()
                                                .getModule("transport_probe")
                                                .callAttr("announce")
                                                .toString()
                                        }
                                        "Announce submitted. This does not prove a peer received it."
                                    } catch (error: Exception) {
                                        "FAILED: ${error.javaClass.simpleName}: ${error.message}"
                                    }
                                }
                            },
                            onCopyAddress = {
                                lxmfAddress?.let { address ->
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("LXMF address", address))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessengerHome(
    fingerprint: String,
    playerId: String,
    nodeId: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    conversations: List<Conversation>,
    contactCount: Int,
    runtimeStatus: String,
    onCheckRuntime: () -> Unit,
    transportStatus: String,
    lxmfAddress: String?,
    transportStarted: Boolean,
    onStartTransport: () -> Unit,
    onAnnounce: () -> Unit,
    onCopyAddress: () -> Unit,
    onOpen: (Long) -> Unit,
    onCreateConversation: (String) -> Unit,
    onContacts: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var showIdentityDetails by remember { mutableStateOf(false) }
    var showTransportDetails by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Urban Alchemy", style = MaterialTheme.typography.headlineMedium)
                    Text("Messenger", style = MaterialTheme.typography.titleMedium)
                    Text("Offline-first · messages remain local until transport is integrated", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Conversations", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onContacts) { Text("Contacts · $contactCount") }
                }
            }
            if (conversations.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No conversations yet. Start with a contact, or create a private local notes thread.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(conversations, key = { it.id }) { conversation ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { onOpen(conversation.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                                Text(conversation.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(
                                    if (conversation.recipientAddress != null) "LXMF · address saved, unverified"
                                    else "Private local thread · stays on this device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("New local conversation") },
                            placeholder = { Text("Give it a name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val title = newName.trim()
                                if (title.isNotEmpty()) {
                                    onCreateConversation(title)
                                    newName = ""
                                }
                            },
                            enabled = newName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Create private thread") }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        TextButton(
                            onClick = { showTransportDetails = !showTransportDetails },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    if (showTransportDetails) "Hide connection details" else "Connection · Reticulum / LXMF",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    if (transportStarted) "Active locally · no peer confirmed"
                                    else "Not started · messages remain local",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (showTransportDetails) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(transportStatus, style = MaterialTheme.typography.bodySmall)
                                if (lxmfAddress != null) {
                                    Text("Your LXMF address", style = MaterialTheme.typography.labelMedium)
                                    Text(lxmfAddress, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = onCopyAddress) { Text("Copy address") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onStartTransport, enabled = !transportStarted) {
                                        Text("Start transport")
                                    }
                                    TextButton(onClick = onAnnounce, enabled = transportStarted) {
                                        Text("Announce")
                                    }
                                }
                                Text(
                                    "Start loads your saved identity. Announce advertises your address; neither sends a message.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                androidx.compose.material3.HorizontalDivider()
                                Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                                Text(runtimeStatus, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onCheckRuntime) { Text("Check embedded runtime") }
                                Text(
                                    "Import check only. It does not connect, announce, or send messages.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        TextButton(
                            onClick = { showIdentityDetails = !showIdentityDetails },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (showIdentityDetails) "Hide identity details" else "Identity details",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        if (showIdentityDetails) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("Player · ${playerId.take(8)}…", style = MaterialTheme.typography.bodyMedium)
                                Text("This device · ${nodeId.take(8)}…", style = MaterialTheme.typography.bodyMedium)
                                androidx.compose.material3.HorizontalDivider()
                                Text("Android device-key fingerprint", style = MaterialTheme.typography.labelMedium)
                                Text(fingerprint, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Separate from your Reticulum/LXMF identity. Private keys stay on this device.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Pull down to refresh your local conversations and contacts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Keep a small scroll runway on short inboxes so swipe gestures
            // remain discoverable even when the visible cards fit the screen.
            item { Spacer(modifier = Modifier.padding(bottom = 112.dp)) }
        }
    }
}

@Composable
private fun ContactsScreen(
    contacts: List<Contact>,
    onShareInvite: () -> Unit,
    onBack: () -> Unit,
    onValidateInvite: (String, (String?, String?) -> Unit) -> Unit,
    onSaveVerifiedInvite: (String) -> Unit,
    onAddContact: (String, String) -> Unit,
    onStartConversation: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var inviteText by remember { mutableStateOf("") }
    var validatedInvite by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ Conversations") }
            Text("Contacts", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Add people to your address book. Share your invite so others can add you.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Add from invite", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Paste the contact invite someone shared with you. The public key and LXMF address are checked before saving.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = inviteText,
                        onValueChange = { inviteText = it; validatedInvite = null; error = null },
                        label = { Text("Paste contact invite") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                    Button(
                        onClick = {
                            validating = true
                            error = null
                            validatedInvite = null
                            onValidateInvite(inviteText) { result, failure ->
                                validating = false
                                validatedInvite = result
                                error = failure
                            }
                        },
                        enabled = inviteText.isNotBlank() && !validating,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (validating) "Checking invite..." else "Verify invite") }
                    if (validatedInvite != null) {
                        val verified = JSONObject(validatedInvite!!)
                        Text("Verified contact", style = MaterialTheme.typography.titleSmall)
                        Text(verified.getString("display_name"), style = MaterialTheme.typography.titleMedium)
                        Text("LXMF · ${verified.getString("lxmf_destination")}", style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                try {
                                    onSaveVerifiedInvite(validatedInvite!!)
                                    inviteText = ""
                                    validatedInvite = null
                                    error = null
                                } catch (exception: Exception) {
                                    error = exception.message ?: "Could not save contact."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add verified contact") }
                    }
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Add contact", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onShareInvite, modifier = Modifier.fillMaxWidth()) {
                        Text("Share my contact invite")
                    }
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
                        label = { Text("LXMF address · 32 hex characters") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
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
                        enabled = name.isNotBlank() && address.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save contact") }
                }
            }
        }
        item { Text("Saved contacts · ${contacts.size}", style = MaterialTheme.typography.titleLarge) }
        if (contacts.isEmpty()) {
            item {
                Text("No contacts yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(contacts, key = { it.id }) { contact ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(contact.name, style = MaterialTheme.typography.titleMedium)
                        Text(contact.destination, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (contact.verified) "Verified Reticulum identity" else "Unverified · address saved locally",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { onStartConversation(contact.id) }) {
                            Text("Start conversation")
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
    val hasValidRecipient = conversation.recipientAddress
        ?.let { Regex("^[0-9a-f]{32}$").matches(it) } == true
    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    conversation.recipientAddress?.let { "LXMF destination · unverified" }
                        ?: "Private local conversation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (hasValidRecipient)
                                "No messages yet. Write a message to queue it locally for this saved recipient."
                            else
                                "Private notes stay on this device. They are not sent to anyone.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt))} · ${if (message.status == "LOCAL_ONLY") "saved locally · not sent" else message.status.lowercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                placeholder = { Text("Write a message") },
                maxLines = 4
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank()
            ) { Text(if (hasValidRecipient) "Send" else "Save note") }
        }
        if (!hasValidRecipient) {
            Text(
                "Private local thread · notes are saved only on this device and are never sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (!hasValidRecipient)
                "Local only · not sent"
            else
                "Queued locally · Destination saved, not yet sent",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
