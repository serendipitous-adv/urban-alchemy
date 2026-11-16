package org.urbanalchemy.messenger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * A cryptographically authenticated receipt for one message.
 * The signer is the intended recipient of the original message.
 */
data class SignedDeliveryAcknowledgement(
    val messageId: String,
    val originalSenderFingerprint: String,
    val originalRecipientFingerprint: String,
    val acknowledgedAtEpochMillis: Long,
    val signerFingerprint: String,
    val signature: ByteArray
) {
    init {
        require(messageId.isNotBlank() && messageId.length <= 128)
        require(originalSenderFingerprint.isNotBlank() && originalRecipientFingerprint.isNotBlank())
        require(originalSenderFingerprint != originalRecipientFingerprint)
        require(acknowledgedAtEpochMillis >= 0)
        require(signerFingerprint == originalRecipientFingerprint) {
            "Only the intended recipient may acknowledge this message."
        }
        require(signature.size in 8..512)
    }

    fun toDeliveryAcknowledgement(): DeliveryAcknowledgement = DeliveryAcknowledgement(
        messageId = messageId,
        recipientIdentity = originalRecipientFingerprint,
        acknowledgedAtEpochMillis = acknowledgedAtEpochMillis
    )

    companion object {
        private const val DOMAIN = "UA-DELIVERY-ACK-v1"
        private const val ALGORITHM = "SHA256withECDSA"

        fun sign(
            messageId: String,
            originalSenderFingerprint: String,
            originalRecipientFingerprint: String,
            acknowledgedAtEpochMillis: Long,
            recipientSigningPrivateKey: PrivateKey
        ): SignedDeliveryAcknowledgement {
            val unsigned = fields(
                messageId, originalSenderFingerprint, originalRecipientFingerprint,
                acknowledgedAtEpochMillis, originalRecipientFingerprint
            )
            val signer = Signature.getInstance(ALGORITHM)
            signer.initSign(recipientSigningPrivateKey)
            signer.update(unsigned)
            return SignedDeliveryAcknowledgement(
                messageId, originalSenderFingerprint, originalRecipientFingerprint,
                acknowledgedAtEpochMillis, originalRecipientFingerprint, signer.sign()
            )
        }

        fun verify(
            acknowledgement: SignedDeliveryAcknowledgement,
            expectedMessageId: String,
            expectedOriginalSenderFingerprint: String,
            expectedRecipientFingerprint: String,
            recipientSigningPublicKey: PublicKey,
            nowEpochMillis: Long,
            messageCreatedAtEpochMillis: Long
        ): DeliveryAcknowledgement {
            require(nowEpochMillis >= 0 && messageCreatedAtEpochMillis >= 0)
            require(acknowledgement.messageId == expectedMessageId) { "Acknowledgement message ID does not match." }
            require(acknowledgement.originalSenderFingerprint == expectedOriginalSenderFingerprint) {
                "Acknowledgement refers to another sender."
            }
            require(acknowledgement.originalRecipientFingerprint == expectedRecipientFingerprint) {
                "Acknowledgement refers to another recipient."
            }
            require(acknowledgement.signerFingerprint == expectedRecipientFingerprint) {
                "Acknowledgement signer is not the intended recipient."
            }
            require(acknowledgement.acknowledgedAtEpochMillis in messageCreatedAtEpochMillis..nowEpochMillis) {
                "Acknowledgement timestamp is outside the valid window."
            }
            require(SignedSessionHandshake.fingerprint(recipientSigningPublicKey) == expectedRecipientFingerprint) {
                "Acknowledgement key is not pinned to the recipient fingerprint."
            }
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(recipientSigningPublicKey)
            verifier.update(fields(
                acknowledgement.messageId,
                acknowledgement.originalSenderFingerprint,
                acknowledgement.originalRecipientFingerprint,
                acknowledgement.acknowledgedAtEpochMillis,
                acknowledgement.signerFingerprint
            ))
            require(verifier.verify(acknowledgement.signature)) { "Acknowledgement signature is invalid." }
            return acknowledgement.toDeliveryAcknowledgement()
        }

        private fun fields(
            id: String, sender: String, recipient: String, time: Long, signerFingerprint: String
        ): ByteArray = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                listOf(DOMAIN, id, sender, recipient, signerFingerprint).forEach {
                    val bytes = it.toByteArray(StandardCharsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                out.writeLong(time)
            }
            buffer.toByteArray()
        }
    }
}

