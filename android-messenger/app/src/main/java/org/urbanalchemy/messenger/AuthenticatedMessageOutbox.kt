package org.urbanalchemy.messenger

import java.security.PublicKey

/**
 * Connects authenticated message encryption to the durable transport outbox.
 *
 * Enqueue persists the complete signed envelope. Decryption always checks row/envelope
 * metadata, expiry, the expected peer identity, and the message signature before
 * returning plaintext. This class does not transmit messages or establish sessions.
 */
class AuthenticatedMessageOutbox(private val outbox: PersistentOutbox) {

    fun enqueue(
        message: SessionMessageCrypto.Message,
        expiresAtEpochMillis: Long? = null
    ): OutboxRecord {
        val envelope = EncryptedMessageEnvelope.fromCryptoMessage(message, expiresAtEpochMillis)
        val record = envelope.toOutboxRecord()
        outbox.enqueue(record)
        return record
    }

    fun list(states: Set<DeliveryState>? = null): List<OutboxRecord> = outbox.list(states)

    fun decode(record: OutboxRecord): SessionMessageCrypto.Message {
        val envelope = EncryptedMessageEnvelope.decodeBase64(record.payloadBase64)
        require(envelope.messageId == record.messageId) { "Outbox and envelope message IDs differ." }
        require(envelope.recipientIdentity == record.recipientIdentity) {
            "Outbox and envelope recipients differ."
        }
        require(envelope.createdAtEpochMillis == record.createdAtEpochMillis) {
            "Outbox and envelope timestamps differ."
        }
        require(envelope.expiresAtEpochMillis == record.expiresAtEpochMillis) {
            "Outbox and envelope expiry values differ."
        }
        return SessionMessageCrypto.Message(
            messageId = envelope.messageId,
            senderFingerprint = envelope.senderIdentity,
            recipientFingerprint = envelope.recipientIdentity,
            createdAtEpochMillis = envelope.createdAtEpochMillis,
            nonce = envelope.nonce.copyOf(),
            ciphertext = envelope.ciphertext.copyOf(),
            signature = envelope.signature.copyOf()
        )
    }

    fun decrypt(
        record: OutboxRecord,
        sessionKey: ByteArray,
        expectedRecipientFingerprint: String,
        expectedSenderFingerprint: String,
        senderSigningPublicKey: PublicKey,
        nowEpochMillis: Long
    ): String {
        require(nowEpochMillis >= 0) { "Current time must not be negative." }
        require(record.expiresAtEpochMillis == null || nowEpochMillis < record.expiresAtEpochMillis) {
            "Message has expired."
        }
        val message = decode(record)
        return SessionMessageCrypto.decrypt(
            sessionKey = sessionKey,
            message = message,
            expectedRecipientFingerprint = expectedRecipientFingerprint,
            expectedSenderFingerprint = expectedSenderFingerprint,
            senderSigningPublicKey = senderSigningPublicKey
        )
    }
}
