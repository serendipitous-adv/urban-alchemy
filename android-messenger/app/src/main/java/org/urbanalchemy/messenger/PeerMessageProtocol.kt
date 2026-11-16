package org.urbanalchemy.messenger

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class PeerChatHistoryItem(
    val messageId: String,
    val text: String,
    val outgoing: Boolean,
    val status: String,
    val createdAtEpochMillis: Long
)

/**
 * Binds the authenticated session to UA message frames, durable outbox/inbox,
 * and signed recipient acknowledgements.
 */
class PeerMessageProtocol(
    context: Context,
    private val session: AuthenticatedSessionEstablisher.EstablishedSession,
    private val localSigningKeyPair: java.security.KeyPair,
    private val pinnedPeerSigningPublicKey: java.security.PublicKey,
    private val sendFrame: (ByteArray) -> Unit,
    private val onMessageReceived: (ReceivedMessage) -> Unit = {},
    private val onDeliveryUpdated: (OutboxRecord) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val outbox: PersistentOutbox = PersistentOutbox(context),
    private val inbox: IncomingMessageStore = PersistentIncomingMessageStore(context)
) : AutoCloseable {

    private val localFingerprint = SignedSessionHandshake.fingerprint(localSigningKeyPair.public)
    private val peerFingerprint = SignedSessionHandshake.fingerprint(pinnedPeerSigningPublicKey)
    private val receiver = AuthenticatedMessageReceiver(inbox)
    @Volatile private var closed = false
    private val retryScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ua-message-retry").apply { isDaemon = true }
    }
    private var scheduledWork: ScheduledFuture<*>? = null

    init {
        require(session.localFingerprint == localFingerprint) { "Session belongs to another local identity." }
        require(session.peerFingerprint == peerFingerprint) { "Session peer does not match the pinned identity." }
    }

    /** Restores this peer's durable incoming and outgoing conversation history. */
    @Synchronized
    fun loadHistory(): List<PeerChatHistoryItem> {
        checkActive()
        val incoming = inbox.listForPeer(peerFingerprint).map { message ->
            PeerChatHistoryItem(message.messageId, message.text, false, "received", message.createdAtEpochMillis)
        }
        val outgoing = outbox.listForRecipient(peerFingerprint).map { record ->
            PeerChatHistoryItem(
                record.messageId,
                LocalQueuedMessageCodec.open(record.payloadBase64),
                true,
                record.state.name.lowercase(),
                record.createdAtEpochMillis
            )
        }
        return (incoming + outgoing).distinctBy { it.messageId }.sortedBy { it.createdAtEpochMillis }
    }

    /** Encrypt locally and durably queue a message; no session-specific ciphertext is persisted. */
    @Synchronized
    fun queueMessage(text: String, messageId: String = UUID.randomUUID().toString()): String {
        checkActive()
        require(text.isNotBlank()) { "Message cannot be empty." }
        val now = nowMillis()
        val localEncryptedPayload = LocalQueuedMessageCodec.seal(text)
        outbox.enqueue(
            OutboxRecord(
                messageId = messageId,
                recipientIdentity = peerFingerprint,
                payloadBase64 = localEncryptedPayload,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = safeAdd(now, MESSAGE_TTL_MILLIS)
            )
        )
        return messageId
    }

    /** Converts timed-out sends to bounded delayed retries before attempting due queue items. */
    @Synchronized
    fun recoverTimedOutDeliveries(
        acknowledgementTimeoutMillis: Long = DEFAULT_ACK_TIMEOUT_MILLIS
    ): List<OutboxRecord> {
        checkActive()
        val recovered = outbox.recoverUnacknowledged(
            nowEpochMillis = nowMillis(),
            acknowledgementTimeoutMillis = acknowledgementTimeoutMillis,
            recipientIdentity = peerFingerprint
        )
        recovered.forEach(onDeliveryUpdated)
        scheduleNextWork()
        return recovered
    }

    /** Sends due queued messages; receiver deduplicates retransmissions by message ID. */
    @Synchronized
    fun flushQueued(limit: Int = 20): Int {
        checkActive()
        recoverTimedOutDeliveries()
        val claims = outbox.claimDue(nowMillis(), limit = limit, recipientIdentity = peerFingerprint)
        var sent = 0
        claims.forEach { claim ->
            try {
                val plaintext = LocalQueuedMessageCodec.open(claim.record.payloadBase64)
                val key = session.copySessionKey()
                val envelope = try {
                    val encrypted = SessionMessageCrypto.encrypt(
                        key, plaintext, claim.record.messageId, claim.record.createdAtEpochMillis,
                        localFingerprint, peerFingerprint, localSigningKeyPair.private
                    )
                    EncryptedMessageEnvelope.fromCryptoMessage(encrypted, claim.record.expiresAtEpochMillis)
                } finally { key.fill(0) }
                val frame = UaProtocolFrame(
                    UaProtocolFrame.Type.ENCRYPTED_MESSAGE,
                    envelope.encodeBase64().toByteArray(StandardCharsets.UTF_8)
                )
                sendFrame(frame.encode())
                outbox.completeClaim(claim, DeliveryState.ROUTER_ACCEPTED)
                val updated = outbox.transition(claim.record.messageId, DeliveryState.SENT, nowEpochMillis = nowMillis())
                onDeliveryUpdated(updated)
                sent++
            } catch (error: Exception) {
                runCatching {
                    outbox.completeClaim(claim, DeliveryState.FAILED, error.message ?: "Transport send failed.")
                    outbox.scheduleRetry(claim.record.messageId, nowMillis())
                    outbox.listForRecipient(peerFingerprint).firstOrNull { it.messageId == claim.record.messageId }
                        ?.let(onDeliveryUpdated)
                }
                if (error is IllegalStateException) throw error
            }
        }
        scheduleNextWork()
        return sent
    }

    @Synchronized
    fun receiveFrame(encodedFrame: ByteArray) {
        checkActive()
        val frame = UaProtocolFrame.decode(encodedFrame)
        when (frame.type) {
            UaProtocolFrame.Type.ENCRYPTED_MESSAGE -> receiveMessage(frame.payload)
            UaProtocolFrame.Type.DELIVERY_ACKNOWLEDGEMENT -> receiveAcknowledgement(frame.payload)
            UaProtocolFrame.Type.SESSION_OFFER -> error("Session offers are handled by the handshake controller.")
        }
    }

    private fun receiveMessage(payload: ByteArray) {
        val encodedEnvelope = String(payload, StandardCharsets.UTF_8)
        val key = session.copySessionKey()
        try {
            val result = receiver.receive(
                encodedEnvelope, key, peerFingerprint, localFingerprint,
                pinnedPeerSigningPublicKey, nowMillis()
            )
            val envelope = EncryptedMessageEnvelope.decodeBase64(encodedEnvelope)
            if (result == AuthenticatedMessageReceiver.Result.STORED) {
                inbox.get(envelope.messageId)?.let(onMessageReceived)
            }
            val acknowledgement = SignedDeliveryAcknowledgement.sign(
                envelope.messageId, peerFingerprint, localFingerprint,
                nowMillis(), localSigningKeyPair.private
            )
            sendFrame(
                UaProtocolFrame(
                    UaProtocolFrame.Type.DELIVERY_ACKNOWLEDGEMENT,
                    SignedDeliveryAcknowledgementCodec.encode(acknowledgement)
                ).encode()
            )
        } finally { key.fill(0) }
    }

    private fun receiveAcknowledgement(payload: ByteArray) {
        val acknowledgement = SignedDeliveryAcknowledgementCodec.decode(payload)
        val updated = outbox.acknowledgeVerified(
            acknowledgement, localFingerprint, pinnedPeerSigningPublicKey, nowMillis()
        )
        onDeliveryUpdated(updated)
        scheduleNextWork()
    }

    /** Schedules receipt-timeout recovery and delayed retries while this authenticated peer is connected. */
    @Synchronized
    private fun scheduleNextWork() {
        if (closed) return
        val now = nowMillis()
        val nextAt = outbox.listForRecipient(peerFingerprint)
            .filter { it.expiresAtEpochMillis == null || it.expiresAtEpochMillis > now }
            .mapNotNull { record ->
                when (record.state) {
                    DeliveryState.QUEUED -> record.nextAttemptAtEpochMillis
                    DeliveryState.SENT -> record.sentAtEpochMillis?.let { safeAdd(it, DEFAULT_ACK_TIMEOUT_MILLIS) }
                    else -> null
                }
            }.minOrNull()
        scheduledWork?.cancel(false)
        scheduledWork = nextAt?.let { dueAt ->
            val delay = (dueAt - now).coerceAtLeast(0L)
            retryScheduler.schedule({
                runCatching { flushQueued() }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun checkActive() { check(!closed) { "Peer message protocol is closed." } }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scheduledWork?.cancel(true)
        retryScheduler.shutdownNow()
        session.close()
        outbox.close()
        (inbox as? AutoCloseable)?.close()
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    companion object {
        private const val DEFAULT_ACK_TIMEOUT_MILLIS = 30_000L
        private const val MESSAGE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}
