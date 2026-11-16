package org.urbanalchemy.messenger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated message encryption using a previously authenticated ephemeral session key. */
object SessionMessageCrypto {
    private const val AES = "AES/GCM/NoPadding"
    private const val HMAC = "HmacSHA256"
    private const val SIGNATURE = "SHA256withECDSA"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val DOMAIN = "UA-SESSION-MESSAGE-v1"

    data class Message(
        val messageId: String,
        val senderFingerprint: String,
        val recipientFingerprint: String,
        val createdAtEpochMillis: Long,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val signature: ByteArray
    )

    /** Encrypts for one direction and signs the complete authenticated envelope. */
    fun encrypt(
        sessionKey: ByteArray,
        plaintext: String,
        messageId: String,
        createdAtEpochMillis: Long,
        senderFingerprint: String,
        recipientFingerprint: String,
        senderSigningPrivateKey: PrivateKey
    ): Message {
        validateInputs(sessionKey, messageId, createdAtEpochMillis, senderFingerprint, recipientFingerprint)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val aad = aad(messageId, senderFingerprint, recipientFingerprint, createdAtEpochMillis)
        val key = deriveDirectionalKey(sessionKey, senderFingerprint, recipientFingerprint)
        val ciphertext = try {
            val cipher = Cipher.getInstance(AES)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        } finally { key.fill(0) }
        val unsigned = Message(messageId, senderFingerprint, recipientFingerprint, createdAtEpochMillis, nonce, ciphertext, byteArrayOf())
        val signer = Signature.getInstance(SIGNATURE)
        signer.initSign(senderSigningPrivateKey)
        signer.update(signedBytes(unsigned))
        return unsigned.copy(signature = signer.sign())
    }

    /** Verifies the sender signature before decrypting and authenticating the ciphertext. */
    fun decrypt(
        sessionKey: ByteArray,
        message: Message,
        expectedRecipientFingerprint: String,
        expectedSenderFingerprint: String,
        senderSigningPublicKey: PublicKey
    ): String {
        require(sessionKey.size == 32) { "Session key must be 256 bits." }
        require(message.recipientFingerprint == expectedRecipientFingerprint) { "Message is addressed to another identity." }
        require(message.senderFingerprint == expectedSenderFingerprint) { "Message sender does not match the expected peer." }
        require(message.messageId.isNotBlank() && message.createdAtEpochMillis >= 0) { "Invalid message metadata." }
        require(message.nonce.size == NONCE_BYTES && message.ciphertext.size >= 16) { "Invalid encrypted message." }
        val verifier = Signature.getInstance(SIGNATURE)
        verifier.initVerify(senderSigningPublicKey)
        verifier.update(signedBytes(message))
        require(verifier.verify(message.signature)) { "Message signature is invalid." }
        val aad = aad(message.messageId, message.senderFingerprint, message.recipientFingerprint, message.createdAtEpochMillis)
        val key = deriveDirectionalKey(sessionKey, message.senderFingerprint, message.recipientFingerprint)
        return try {
            val cipher = Cipher.getInstance(AES)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, message.nonce))
            cipher.updateAAD(aad)
            String(cipher.doFinal(message.ciphertext), StandardCharsets.UTF_8)
        } finally { key.fill(0) }
    }

    private fun validateInputs(
        sessionKey: ByteArray, messageId: String, createdAt: Long, sender: String, recipient: String
    ) {
        require(sessionKey.size == 32) { "Session key must be 256 bits." }
        require(messageId.isNotBlank() && messageId.length <= 128) { "Invalid message ID." }
        require(createdAt >= 0) { "Creation time must not be negative." }
        require(sender.isNotBlank() && recipient.isNotBlank() && sender != recipient) { "Invalid message identities." }
    }

    private fun deriveDirectionalKey(sessionKey: ByteArray, sender: String, recipient: String): ByteArray {
        val salt = sha256(encode(DOMAIN, sender, recipient))
        val extract = Mac.getInstance(HMAC)
        extract.init(SecretKeySpec(salt, HMAC))
        val prk = extract.doFinal(sessionKey)
        return try {
            val expand = Mac.getInstance(HMAC)
            expand.init(SecretKeySpec(prk, HMAC))
            expand.update(encode("$DOMAIN encryption key", sender, recipient))
            expand.update(1.toByte())
            expand.doFinal().copyOf(32)
        } finally { prk.fill(0) }
    }

    private fun sha256(value: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(value)

    private fun aad(id: String, sender: String, recipient: String, createdAt: Long) =
        encode("$DOMAIN aad", id, sender, recipient, createdAt)

    private fun signedBytes(message: Message) = encode(
        "$DOMAIN signature", message.messageId, message.senderFingerprint, message.recipientFingerprint,
        message.createdAtEpochMillis, message.nonce, message.ciphertext
    )

    private fun encode(vararg fields: Any): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            fields.forEach { field ->
                when (field) {
                    is String -> { val bytes = field.toByteArray(StandardCharsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
                    is Long -> out.writeLong(field)
                    is ByteArray -> { out.writeInt(field.size); out.write(field) }
                    else -> error("Unsupported field type.")
                }
            }
        }
        buffer.toByteArray()
    }
}
