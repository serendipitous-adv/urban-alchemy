package org.urbanalchemy.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * Versioned wire container for an already-encrypted message body.
 *
 * This class does not encrypt or authenticate data. Callers must supply ciphertext
 * produced by the end-to-end crypto layer; the nonce is the AEAD nonce for that ciphertext.
 * The encoded form is transport-neutral and can be carried by Wi-Fi, Bluetooth, or LXMF.
 */
data class EncryptedMessageEnvelope(
    val messageId: String,
    val senderIdentity: String,
    val recipientIdentity: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val version: Int = CURRENT_VERSION
) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported message envelope version." }
        require(messageId.isNotBlank() && messageId.length <= MAX_ID_LENGTH) { "Invalid message ID." }
        require(senderIdentity.isNotBlank() && senderIdentity.length <= MAX_IDENTITY_LENGTH) { "Invalid sender identity." }
        require(recipientIdentity.isNotBlank() && recipientIdentity.length <= MAX_IDENTITY_LENGTH) { "Invalid recipient identity." }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative." }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > createdAtEpochMillis) { "Expiry must be later than creation." }
        require(nonce.size == GCM_NONCE_BYTES) { "AES-GCM nonce must be 12 bytes." }
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "Invalid ciphertext size." }
    }

    /** Encode the complete binary envelope as Base64 for the durable outbox. */
    fun encodeBase64(): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(MAGIC)
                output.writeInt(version)
                output.writeUTF(messageId)
                output.writeUTF(senderIdentity)
                output.writeUTF(recipientIdentity)
                output.writeLong(createdAtEpochMillis)
                output.writeBoolean(expiresAtEpochMillis != null)
                expiresAtEpochMillis?.let(output::writeLong)
                output.writeInt(nonce.size)
                output.write(nonce)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            buffer.toByteArray()
        }
        require(bytes.size <= MAX_ENCODED_BYTES) { "Encoded envelope is too large." }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Create a queued outbox record; this does not send or deliver the message. */
    fun toOutboxRecord(): OutboxRecord = OutboxRecord(
        messageId = messageId,
        recipientIdentity = recipientIdentity,
        payloadBase64 = encodeBase64(),
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis
    )

    companion object {
        const val CURRENT_VERSION = 1
        private const val GCM_NONCE_BYTES = 12
        private const val MAX_ID_LENGTH = 128
        private const val MAX_IDENTITY_LENGTH = 512
        private const val MAX_CIPHERTEXT_BYTES = 1_048_576
        private const val MAX_ENCODED_BYTES = MAX_CIPHERTEXT_BYTES + 2_048
        private val MAGIC = byteArrayOf(0x55, 0x41, 0x45, 0x4d)

        fun decodeBase64(encoded: String): EncryptedMessageEnvelope {
            require(encoded.isNotBlank() && encoded.length <= ((MAX_ENCODED_BYTES + 2) / 3) * 4) {
                "Invalid encoded envelope size."
            }
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Envelope is not valid Base64.", error)
            }
            require(bytes.size <= MAX_ENCODED_BYTES) { "Encoded envelope is too large." }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Unrecognized message envelope." }
                val version = input.readInt()
                require(version == CURRENT_VERSION) { "Unsupported message envelope version." }
                val messageId = input.readUTF()
                val sender = input.readUTF()
                val recipient = input.readUTF()
                val createdAt = input.readLong()
                val expiresAt = if (input.readBoolean()) input.readLong() else null
                val nonceSize = input.readInt()
                require(nonceSize == GCM_NONCE_BYTES) { "Invalid envelope nonce size." }
                val nonce = ByteArray(nonceSize).also(input::readFully)
                val ciphertextSize = input.readInt()
                require(ciphertextSize in 1..MAX_CIPHERTEXT_BYTES) { "Invalid envelope ciphertext size." }
                val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
                require(input.read() == -1) { "Unexpected trailing envelope data." }
                return EncryptedMessageEnvelope(
                    messageId, sender, recipient, createdAt, expiresAt, nonce, ciphertext, version
                )
            }
        }
    }
}