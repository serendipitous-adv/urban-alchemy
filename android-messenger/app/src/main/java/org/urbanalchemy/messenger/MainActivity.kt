package org.urbanalchemy.messenger

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import org.json.JSONObject
import org.json.JSONArray
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.Python
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
    private var sharedInvite by mutableStateOf<String?>(null)
    companion object {
        // Static for the lifetime of this Android process: Activity recreation must
        // not turn active messages into uncertain ones a second time.
        @Volatile private var processRecoveryCompleted = false
    }
    private var reticulumMulticastLock: WifiManager.MulticastLock? = null

    private fun acquireReticulumMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = reticulumMulticastLock ?: wifi.createMulticastLock("UrbanAlchemyReticulum").also {
            it.setReferenceCounted(false)
            reticulumMulticastLock = it
        }
        if (!lock.isHeld) lock.acquire()
    }



    private var lxmfSyncJob: Job? = null
    private val lxmfSyncMutex = Mutex()

    /**
     * Keep receive processing active while the Activity is visible. Android may
     * stop this loop when the app leaves the foreground; durable background
     * reception is a separate service/notification design decision.
     */
    private fun startLxmfSync(store: LocalStore) {
        if (lxmfSyncJob?.isActive == true) return
        lxmfSyncJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        syncLxmf(store)
                    } catch (_: Exception) {
                        // Keep the encrypted outbox intact and retry on the next cycle.
                    }
                    delay(5_000)
                }
            }
        }
    }

    /** Serialize bridge access when sends/connect actions overlap the receive loop. */
    private suspend fun syncLxmf(store: LocalStore): Int = lxmfSyncMutex.withLock {
        withContext(Dispatchers.IO) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val module = Python.getInstance().getModule("transport_probe")
        val runtime = JSONObject(
            module.callAttr(
                "start_runtime",
                filesDir.resolve("reticulum").absolutePath,
                "Urban Alchemy"
            ).toString()
        )
        val localDestination = runtime.getString("destination")
        store.bindReticulumIdentity(
            runtime.getString("reticulum_identity_hash"),
            runtime.getString("reticulum_public_key"),
            localDestination
        )

        var processed = 0
        for (pending in store.pendingOutbound()) {
            // Persist the ambiguous boundary BEFORE calling LXMF. If the process
            // dies during/after handle_outbound(), recovery must never resend it.
            store.beginOutboundSubmission(pending.id)
            val result = try {
                JSONObject(
                    module.callAttr("send_message", pending.recipient, pending.text, pending.id, pending.publicKey ?: "").toString()
                )
            } catch (error: Exception) {
                store.updateOutbound(pending.id, "DELIVERY_UNKNOWN")
                throw error
            }
            if (result.optBoolean("accepted")) {
                store.updateOutbound(
                    pending.id, "SUBMITTED",
                    result.optString("lxmf_hash").takeIf { it.isNotBlank() && it != "null" }
                )
            } else {
                val waiting = result.optString("status")
                store.updateOutbound(
                    pending.id,
                    if (waiting == "WAITING_FOR_IDENTITY") "WAITING_FOR_IDENTITY" else "WAITING_FOR_ROUTE"
                )
            }
            processed++
        }

        val events = JSONArray(module.callAttr("peek_events", 100).toString())
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            when (event.optString("type")) {
                "inbound" -> {
                    val source = event.optString("source")
                    val destination = event.optString("destination")
                    val hash = event.optString("message_hash")
                    val signatureValidated = event.optBoolean("signature_validated", false)
                    // Never persist or surface an inbound message unless LXMF has
                    // validated its signature and it targets this device's delivery identity.
                    if (
                        signatureValidated &&
                        destination == localDestination &&
                        source.isNotBlank() && source != "null" &&
                        hash.isNotBlank() && hash != "null"
                    ) {
                        store.saveInbound(
                            source, hash, event.optString("content"),
                            event.optLong("timestamp", System.currentTimeMillis())
                        )
                        processed++
                    } else {
                        // Explicitly discard invalid/misdirected callback events so
                        // they do not remain at the head of the bridge queue forever.
                        processed++
                    }
                }
                "outbound_status" -> {
                    val state = when (event.optString("state_name")) {
                        "DELIVERED" -> "DELIVERED"
                        "SENT" -> "SENT"
                        "FAILED", "CANCELLED" -> "FAILED"
                        "REJECTED" -> "REJECTED"
                        "OUTBOUND", "SENDING" -> "SUBMITTED"
                        else -> null
                    }
                    val clientId = event.optString("client_message_id")
                    if (state != null && clientId.isNotBlank() && clientId != "null") {
                        store.updateOutbound(
                            clientId, state,
                            event.optString("lxmf_hash").takeIf { it.isNotBlank() && it != "null" }
                        )
                        processed++
                    }
                }
            }
        }
        if (events.length() > 0) {
            module.callAttr("ack_events", events.length())
        }
        processed
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            acquireReticulumMulticastLock()
        } catch (_: Exception) {
            // Runtime startup reports transport errors; never crash the UI here.
        }
    }

    override fun onStop() {
        try {
            reticulumMulticastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedInvite = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        }
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
        sharedInvite = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        } else null
        val store = LocalStore(this)
        val identity = DeviceIdentity.getOrCreate()
        setContent {
            var uaIdentity by remember { mutableStateOf<UaLocalIdentity?>(null) }
            var conversations by remember { mutableStateOf(emptyList<Conversation>()) }
            var contacts by remember { mutableStateOf(emptyList<Contact>()) }
            var selectedId by remember { mutableStateOf<Long?>(null) }
            var messages by remember { mutableStateOf(emptyList<LocalMessage>()) }
            var showContacts by remember { mutableStateOf(sharedInvite != null) }
            var runtimeStatus by remember { mutableStateOf("Not checked") }
            var transportStatus by remember { mutableStateOf("Not started") }
            var lxmfAddress by remember { mutableStateOf<String?>(null) }
            var transportStarted by remember { mutableStateOf(false) }
            var isRefreshing by remember { mutableStateOf(false) }
            BackHandler(enabled = selectedId != null || showContacts) {
                if (selectedId != null) selectedId = null else showContacts = false
            }

            LaunchedEffect(Unit) {
                // Recover delivery state and load the persistent identity before
                // starting transport. All database work stays off the UI thread.
                val initial = withContext(Dispatchers.IO) {
                    synchronized(MainActivity::class.java) {
                        if (!processRecoveryCompleted) {
                            store.markInFlightDeliveryUnknown()
                            processRecoveryCompleted = true
                        }
                    }
                    Triple(
                        store.getOrCreateUaIdentity(),
                        store.listConversations(),
                        store.listContacts()
                    )
                }
                uaIdentity = initial.first
                conversations = initial.second
                contacts = initial.third
                startLxmfSync(store)
            }

            LaunchedEffect(sharedInvite) {
                if (sharedInvite != null) {
                    selectedId = null
                    showContacts = true
                }
            }

            // SQLite remains the durable source of truth. Refresh snapshots off
            // the main thread so inbound messages and delivery callbacks appear
            // without a manual refresh or a send action.
            // Keying by selectedId cancels an old chat's refresh before it can
            // overwrite the message list for a newly selected conversation.
            LaunchedEffect(selectedId) {
                while (isActive) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        val snapshot = withContext(Dispatchers.IO) {
                            Triple(
                                store.listConversations(),
                                store.listContacts(),
                                selectedId?.let { store.listMessages(it) } ?: emptyList()
                            )
                        }
                        conversations = snapshot.first
                        contacts = snapshot.second
                        messages = snapshot.third
                    }
                    delay(1_000)
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val selected = conversations.firstOrNull { it.id == selectedId }
                    when {
                        uaIdentity == null -> Text("Starting Urban Alchemy…")
                        selected != null -> ChatScreen(
                            conversation = selected,
                            messages = messages,
                            onBack = { selectedId = null },
                            onSend = { text ->
                                if (selected.recipientAddress?.let { Regex("^[0-9a-f]{32}$").matches(it) } == true) {
                                    store.addQueuedMessage(selected.id, text)
                                    messages = store.listMessages(selected.id)
                                    conversations = store.listConversations()
                                    transportStatus = "Message saved to the encrypted outbox. Submitting through LXMF..."
                                    lifecycleScope.launch {
                                        try {
                                            syncLxmf(store)
                                            startLxmfSync(store)
                                            if (selectedId == selected.id) {
                                                messages = store.listMessages(selected.id)
                                            }
                                            conversations = store.listConversations()
                                            transportStatus = "LXMF outbox checked. Delivery status reflects transport callbacks."
                                        } catch (error: Exception) {
                                            transportStatus = "Message remains safely queued: ${error.message ?: error.javaClass.simpleName}"
                                        }
                                    }
                                } else {
                                    store.addLocalMessage(selected.id, text)
                                    messages = store.listMessages(selected.id)
                                    conversations = store.listConversations()
                                }
                            }
                        )
                        showContacts -> ContactsScreen(
                            contacts = contacts,
                            incomingInvite = sharedInvite,
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
                                                // Sharing an invite is the user's explicit signal to make
                                                // this LXMF destination discoverable to the recipient.
                                                module.callAttr("announce")
                                                module.callAttr(
                                                    "create_contact_invite",
                                                    "Urban Alchemy",
                                                    uaIdentity.playerId
                                                ).toString()
                                            }
                                            transportStarted = true
                                            startLxmfSync(store)
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, invite)
                                            }
                                            startActivity(Intent.createChooser(sendIntent, "Share your Urban Alchemy contact invite"))
                                            transportStatus = "Your LXMF address was announced and the public contact invite is ready to share."
                                        } catch (error: Exception) {
                                            transportStatus = "Could not create invite: ${error.message ?: error.javaClass.simpleName}"
                                        }
                                    }
                                } catch (error: Exception) {
                                    transportStatus = "Could not prepare Reticulum: ${error.message ?: error.javaClass.simpleName}"
                                }
                            },
                            onBack = { showContacts = false; sharedInvite = null },
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
                            playerId = uaIdentity!!.playerId,
                            nodeId = uaIdentity!!.nodeId,
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
                                            transportStatus = "Reticulum started. Checking the durable LXMF outbox..."
                                            syncLxmf(store)
                                            startLxmfSync(store)
                                            conversations = store.listConversations()
                                            transportStatus = "Reticulum is running. Outbox submitted where routes were available; waiting messages remain queued."
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
    var showSettings by remember { mutableStateOf(false) }
    val messageThreads = conversations.filter {
        it.recipientAddress != null || it.title.startsWith("Message request ·")
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, top = 22.dp, end = 20.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Urban Alchemy", style = MaterialTheme.typography.headlineSmall)
                        Text("Messages", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "Close" else "Settings")
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            if (messageThreads.isEmpty()) "A little world starts with a conversation."
                            else "Your conversations",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            if (messageThreads.isEmpty())
                                "Add someone you know. Your messages stay on this device until a connection is available."
                            else "Talk directly. No public directory, account, or central feed required.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onContacts, modifier = Modifier.fillMaxWidth()) {
                            Text("＋  Add a contact")
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Conversations", style = MaterialTheme.typography.titleLarge)
                    Text("$contactCount contacts", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (messageThreads.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✳", style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "When you add someone, your conversation will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(messageThreads, key = { it.id }) { conversation ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                    ) {
                        TextButton(
                            onClick = { onOpen(conversation.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(conversation.title, style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        if (conversation.recipientVerified) "Contact checked" else "Saved contact",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    conversation.preview.ifBlank { "Start a conversation" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
            if (showSettings) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Connection", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (transportStarted) "Messenger runtime is active on this device."
                                else "The messenger connects when you share your invite or start transport.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(transportStatus, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onStartTransport, enabled = !transportStarted) {
                                    Text("Connect")
                                }
                                TextButton(onClick = onAnnounce, enabled = transportStarted) {
                                    Text("Refresh presence")
                                }
                            }
                            if (lxmfAddress != null) {
                                Text("Advanced network address", style = MaterialTheme.typography.labelMedium)
                                Text(lxmfAddress, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onCopyAddress) { Text("Copy address") }
                            }
                            androidx.compose.material3.HorizontalDivider()
                            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                            Text(runtimeStatus, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onCheckRuntime) { Text("Check app runtime") }
                            androidx.compose.material3.HorizontalDivider()
                            Text("This device", style = MaterialTheme.typography.titleSmall)
                            Text("Player · ${playerId.take(8)}…", style = MaterialTheme.typography.bodySmall)
                            Text("Device · ${nodeId.take(8)}…", style = MaterialTheme.typography.bodySmall)
                            Text("Key fingerprint · ${fingerprint.take(16)}…",
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Your private keys stay on this device. Network details are shown here only for troubleshooting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Private local notes are kept on this device and are not shown as conversations.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    contacts: List<Contact>,
    incomingInvite: String?,
    onShareInvite: () -> Unit,
    onBack: () -> Unit,
    onValidateInvite: (String, (String?, String?) -> Unit) -> Unit,
    onSaveVerifiedInvite: (String) -> Unit,
    onAddContact: (String, String) -> Unit,
    onStartConversation: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var inviteText by remember(incomingInvite) { mutableStateOf(incomingInvite.orEmpty()) }
    var validatedInvite by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showManualAddress by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹  Messages") }
            Text("People", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your contacts are yours. Add someone by exchanging a private invite.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Share your invite", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Let someone add you. They can share their invite back with you to complete the exchange.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onShareInvite, modifier = Modifier.fillMaxWidth()) {
                        Text("Share my contact invite")
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Add someone", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Paste an invite someone shared with you. The app checks that the public key and messaging address belong together.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = inviteText,
                        onValueChange = { inviteText = it; validatedInvite = null; error = null },
                        label = { Text("Contact invite") },
                        placeholder = { Text("Paste invite here") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5
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
                    ) { Text(if (validating) "Checking…" else "Check invite") }
                    if (validatedInvite != null) {
                        val verified = JSONObject(validatedInvite!!)
                        Text(verified.getString("display_name"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The key and messaging address match. The person's display name is self-reported.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        ) { Text("Add contact") }
                    }
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { showManualAddress = !showManualAddress }) {
                Text(if (showManualAddress) "Hide advanced options" else "Advanced: add by network address")
            }
            if (showManualAddress) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Only use this if you already know the person's LXMF address. It does not verify who controls it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; error = null },
                        label = { Text("Contact name") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = address, onValueChange = { address = it; error = null },
                        label = { Text("Network address") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Button(
                        onClick = {
                            try {
                                onAddContact(name, address)
                                name = ""; address = ""; error = null; showManualAddress = false
                            } catch (exception: Exception) {
                                error = exception.message ?: "Could not add contact."
                            }
                        },
                        enabled = name.isNotBlank() && address.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save unverified contact") }
                }
            }
            if (error != null && validatedInvite == null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        }
        item { Text("Your people · ${contacts.size}", style = MaterialTheme.typography.titleLarge) }
        if (contacts.isEmpty()) {
            item {
                Text(
                    "No contacts yet. When someone shares an invite, add them here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(contacts, key = { it.id }) { contact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                contact.name.firstOrNull()?.uppercase() ?: "•",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (contact.verified) "Key and address match" else "Saved · not verified",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onStartConversation(contact.id) }) { Text("Message") }
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
    val messageListState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) messageListState.animateScrollToItem(messages.lastIndex)
    }
    val hasValidRecipient = conversation.recipientAddress
        ?.let { Regex("^[0-9a-f]{32}$").matches(it) } == true

    Column(
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹") }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    if (conversation.recipientVerified) "Contact key checked"
                    else if (hasValidRecipient) "Saved contact"
                    else "Private notes · only on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        androidx.compose.material3.HorizontalDivider()
        LazyColumn(
            state = messageListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Say hello", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (hasValidRecipient) "Your messages are kept safely while the network finds a path."
                            else "These notes never leave this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    val incoming = message.status == "RECEIVED" || message.status == "REQUEST"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.88f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomEnd = if (incoming) 18.dp else 5.dp,
                                bottomStart = if (incoming) 5.dp else 18.dp
                            ),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (incoming)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                                val status = when (message.status) {
                                    "LOCAL_ONLY" -> "On this device"
                                    "QUEUED", "WAITING_FOR_ROUTE", "WAITING_FOR_IDENTITY" -> "Waiting for connection"
                                    "SUBMITTING" -> "Sending"
                                    "SUBMITTED" -> "Sent to network"
                                    "DELIVERY_UNKNOWN" -> "Delivery status unknown"
                                    "SENT" -> "Sent · awaiting delivery"
                                    "DELIVERED" -> "Delivered"
                                    "FAILED", "REJECTED" -> "Not sent · kept on this device"
                                    "RECEIVED" -> "Received"
                                    "REQUEST" -> "Message request"
                                    else -> message.status.lowercase().replace('_', ' ')
                                }
                                Text(
                                    "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt))}  ·  $status",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.material3.Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (hasValidRecipient) "Write a message…" else "Write a private note…") },
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
                ) { Text(if (hasValidRecipient) "Send" else "Save") }
            }
        }
    }
}
