package org.urbanalchemy.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Versioned transport-neutral container for authenticated, already-encrypted messages. */
data class EncryptedMessageEnvelope(
    val messageId: String, val senderIdentity: String, val recipientIdentity: String,
    val createdAtEpochMillis: Long, val expiresAtEpochMillis: Long?,
    val nonce: ByteArray, val ciphertext: ByteArray, val signature: ByteArray,
    val version: Int = CURRENT_VERSION
) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported message envelope version." }
        require(messageId.isNotBlank() && messageId.length <= MAX_ID_LENGTH)
        require(senderIdentity.isNotBlank() && senderIdentity.length <= MAX_IDENTITY_LENGTH)
        require(recipientIdentity.isNotBlank() && recipientIdentity.length <= MAX_IDENTITY_LENGTH)
        require(senderIdentity != recipientIdentity)
        require(createdAtEpochMillis >= 0)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > createdAtEpochMillis)
        require(nonce.size == NONCE_BYTES) { "AES-GCM nonce must be 12 bytes." }
        require(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES)
        require(signature.size in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) { "Missing or invalid message signature." }
    }

    fun encodeBase64(): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(MAGIC); out.writeInt(version)
                out.writeUTF(messageId); out.writeUTF(senderIdentity); out.writeUTF(recipientIdentity)
                out.writeLong(createdAtEpochMillis)
                out.writeBoolean(expiresAtEpochMillis != null); expiresAtEpochMillis?.let(out::writeLong)
                out.writeInt(nonce.size); out.write(nonce)
                out.writeInt(ciphertext.size); out.write(ciphertext)
                out.writeInt(signature.size); out.write(signature)
            }
            buffer.toByteArray()
        }
        require(bytes.size <= MAX_ENCODED_BYTES) { "Encoded envelope is too large." }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun toOutboxRecord() = OutboxRecord(
        messageId, recipientIdentity, encodeBase64(), createdAtEpochMillis, expiresAtEpochMillis
    )

    companion object {
        const val CURRENT_VERSION = 2
        private const val NONCE_BYTES = 12
        private const val MIN_CIPHERTEXT_BYTES = 16
        private const val MAX_ID_LENGTH = 128
        private const val MAX_IDENTITY_LENGTH = 512
        private const val MAX_CIPHERTEXT_BYTES = 1_048_576
        private const val MIN_SIGNATURE_BYTES = 8
        private const val MAX_SIGNATURE_BYTES = 512
        private const val MAX_ENCODED_BYTES = MAX_CIPHERTEXT_BYTES + 4_096
        private val MAGIC = byteArrayOf(0x55, 0x41, 0x45, 0x4d)

        fun fromCryptoMessage(message: SessionMessageCrypto.Message, expiresAtEpochMillis: Long? = null) =
            EncryptedMessageEnvelope(
                message.messageId, message.senderFingerprint, message.recipientFingerprint,
                message.createdAtEpochMillis, expiresAtEpochMillis, message.nonce,
                message.ciphertext, message.signature
            )

        fun decodeBase64(encoded: String): EncryptedMessageEnvelope {
            require(encoded.isNotBlank() && encoded.length <= ((MAX_ENCODED_BYTES + 2) / 3) * 4)
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Envelope is not valid Base64.", e)
            }
            require(bytes.size <= MAX_ENCODED_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "Unrecognized message envelope." }
                val version = input.readInt()
                require(version == CURRENT_VERSION) { "Unsupported message envelope version." }
                val id = input.readUTF(); val sender = input.readUTF(); val recipient = input.readUTF()
                val created = input.readLong()
                val expires = if (input.readBoolean()) input.readLong() else null
                val nonceSize = input.readInt(); require(nonceSize == NONCE_BYTES)
                val nonce = ByteArray(nonceSize).also(input::readFully)
                val cipherSize = input.readInt(); require(cipherSize in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES)
                val ciphertext = ByteArray(cipherSize).also(input::readFully)
                val signatureSize = input.readInt(); require(signatureSize in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES)
                val signature = ByteArray(signatureSize).also(input::readFully)
                require(input.read() == -1) { "Unexpected trailing envelope data." }
                return EncryptedMessageEnvelope(id, sender, recipient, created, expires, nonce, ciphertext, signature, version)
            }
        }
    }
}