package org.urbanalchemy.messenger

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Binds the authenticated session to UA message frames, durable outbox/inbox,
 * and signed recipient acknowledgements. The transport remains a byte-frame
 * callback; this class owns the application message semantics.
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

    init {
        require(session.localFingerprint == localFingerprint) { "Session belongs to another local identity." }
        require(session.peerFingerprint == peerFingerprint) { "Session peer does not match the pinned identity." }
    }

    /** Encrypt and durably queue a message. It is not sent until flushQueued is called. */
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

    /**
     * Sends due queued messages. A crash after socket write but before the outbox
     * update causes a safe retransmission; the recipient inbox deduplicates IDs.
     */
    @Synchronized
    fun flushQueued(limit: Int = 20): Int {
        checkActive()
        val now = nowMillis()
        val claims = outbox.claimDue(now, limit = limit, recipientIdentity = peerFingerprint)
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
                    EncryptedMessageEnvelope.fromCryptoMessage(
                        encrypted, claim.record.expiresAtEpochMillis
                    )
                } finally { key.fill(0) }
                val frame = UaProtocolFrame(
                    UaProtocolFrame.Type.ENCRYPTED_MESSAGE,
                    envelope.encodeBase64().toByteArray(StandardCharsets.UTF_8)
                )
                sendFrame(frame.encode())
                outbox.completeClaim(claim, DeliveryState.ROUTER_ACCEPTED)
                val updated = outbox.transition(claim.record.messageId, DeliveryState.SENT)
                onDeliveryUpdated(updated)
                sent++
            } catch (error: Exception) {
                runCatching { outbox.completeClaim(claim, DeliveryState.FAILED, error.message ?: "Transport send failed.") }
                if (error is IllegalStateException) throw error
            }
        }
        return sent
    }

    /** Process one complete UA protocol frame from the connected peer. */
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
            // Re-ack duplicates too, so a lost receipt does not strand the sender.
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
        updated?.let(onDeliveryUpdated)
    }

    private fun checkActive() { check(!closed) { "Peer message protocol is closed." } }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.close()
        outbox.close()
        (inbox as? AutoCloseable)?.close()
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    companion object {
        private const val MESSAGE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}
