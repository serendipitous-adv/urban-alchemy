package org.urbanalchemy.messenger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * First E2E cryptography layer: P-256 ECDH, HKDF-SHA256, AES-256-GCM, and
 * ECDSA identity signatures. This file is transport-independent.
 *
 * Key pairs are passed in by the caller. Production key lifecycle/storage,
 * signed-key exchange, QR verification UI, rotation, and forward secrecy are
 * separate integration work; do not generate a new identity key per message.
 */
object E2eMessageCrypto {
    private const val CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val AGREEMENT_ALGORITHM = "ECDH"
    private const val KDF_ALGORITHM = "HmacSHA256"
    private const val AES_ALGORITHM = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    data class IdentityBundle(
        val fingerprint: String,
        val signingPublicKey: ByteArray,
        val agreementPublicKey: ByteArray,
        val signature: ByteArray
    )

    data class VerifiedPeerIdentity internal constructor(
        val fingerprint: String,
        val signingPublicKey: PublicKey,
        val agreementPublicKey: PublicKey
    )

    data class SecureMessage(
        val messageId: String,
        val senderFingerprint: String,
        val recipientFingerprint: String,
        val createdAtEpochMillis: Long,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val signature: ByteArray
    )

    fun generateAgreementKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(CURVE))
    }.generateKeyPair()

    /** Bind a long-term ECDH public key to the existing signing identity. */
    fun createIdentityBundle(signingKeyPair: KeyPair, agreementPublicKey: PublicKey): IdentityBundle {
        val fingerprint = fingerprint(signingKeyPair.public)
        val agreementBytes = agreementPublicKey.encoded
        val signedBytes = identityBundleBytes(fingerprint, signingKeyPair.public.encoded, agreementBytes)
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(signingKeyPair.private)
        signer.update(signedBytes)
        return IdentityBundle(fingerprint, signingKeyPair.public.encoded, agreementBytes, signer.sign())
    }

    /** Verify both the signing-key fingerprint and its signature over the ECDH key. */
    fun verifyIdentityBundle(bundle: IdentityBundle, expectedFingerprint: String): VerifiedPeerIdentity {
        require(bundle.fingerprint == expectedFingerprint) { "Peer fingerprint does not match the independently verified fingerprint." }
        val signingPublic = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bundle.signingPublicKey))
        val agreementPublic = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bundle.agreementPublicKey))
        require(fingerprint(signingPublic) == bundle.fingerprint) { "Identity fingerprint is invalid." }
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(signingPublic)
        verifier.update(identityBundleBytes(bundle.fingerprint, bundle.signingPublicKey, bundle.agreementPublicKey))
        require(verifier.verify(bundle.signature)) { "Identity bundle signature is invalid." }
        return VerifiedPeerIdentity(bundle.fingerprint, signingPublic, agreementPublic)
    }

    fun encrypt(
        plaintext: String,
        messageId: String,
        createdAtEpochMillis: Long,
        senderFingerprint: String,
        senderSigningPrivateKey: PrivateKey,
        senderAgreementPrivateKey: PrivateKey,
        recipient: VerifiedPeerIdentity
    ): SecureMessage {
        require(messageId.isNotBlank()) { "Message ID is required." }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative." }
        require(senderFingerprint.isNotBlank() && senderFingerprint != recipient.fingerprint) { "Invalid message identities." }
        val nonce = ByteArray(NONCE_BYTES).also(java.security.SecureRandom()::nextBytes)
        val aad = messageAad(messageId, senderFingerprint, recipient.fingerprint, createdAtEpochMillis)
        val key = deriveMessageKey(senderAgreementPrivateKey, recipient.agreementPublicKey, aad)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val unsigned = SecureMessage(messageId, senderFingerprint, recipient.fingerprint, createdAtEpochMillis, nonce, ciphertext, byteArrayOf())
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(senderSigningPrivateKey)
        signer.update(messageSignatureBytes(unsigned))
        return unsigned.copy(signature = signer.sign())
    }

    /** Verify sender signature and recipient binding before attempting decryption. */
    fun decrypt(
        message: SecureMessage,
        recipientFingerprint: String,
        recipientAgreementPrivateKey: PrivateKey,
        sender: VerifiedPeerIdentity
    ): String {
        require(message.recipientFingerprint == recipientFingerprint) { "Message is addressed to another identity." }
        require(message.senderFingerprint == sender.fingerprint) { "Sender identity does not match the verified peer." }
        require(message.nonce.size == NONCE_BYTES) { "Invalid AES-GCM nonce." }
        require(message.ciphertext.size >= 16) { "Ciphertext is missing its authentication tag." }
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(sender.signingPublicKey)
        verifier.update(messageSignatureBytes(message.copy(signature = byteArrayOf())))
        require(verifier.verify(message.signature)) { "Message signature is invalid." }
        val aad = messageAad(message.messageId, message.senderFingerprint, message.recipientFingerprint, message.createdAtEpochMillis)
        val key = deriveMessageKey(recipientAgreementPrivateKey, sender.agreementPublicKey, aad)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, message.nonce))
        cipher.updateAAD(aad)
        return String(cipher.doFinal(message.ciphertext), StandardCharsets.UTF_8)
    }

    fun fingerprint(publicKey: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey.encoded).take(16).joinToString(":") { "%02x".format(it) }

    private fun deriveMessageKey(privateKey: PrivateKey, publicKey: PublicKey, context: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance(AGREEMENT_ALGORITHM)
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val sharedSecret = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(context)
        val extract = Mac.getInstance(KDF_ALGORITHM)
        extract.init(SecretKeySpec(salt, KDF_ALGORITHM))
        val pseudorandomKey = extract.doFinal(sharedSecret)
        val expand = Mac.getInstance(KDF_ALGORITHM)
        expand.init(SecretKeySpec(pseudorandomKey, KDF_ALGORITHM))
        expand.update("UrbanAlchemy E2E message key v1".toByteArray(StandardCharsets.UTF_8))
        expand.update(1.toByte())
        return expand.doFinal().copyOf(32)
    }

    private fun identityBundleBytes(fingerprint: String, signingKey: ByteArray, agreementKey: ByteArray): ByteArray =
        encodeFields("UA-IDENTITY-BUNDLE-v1", fingerprint, signingKey, agreementKey)

    private fun messageAad(id: String, sender: String, recipient: String, createdAt: Long): ByteArray =
        encodeFields("UA-MESSAGE-AAD-v1", id, sender, recipient, createdAt)

    private fun messageSignatureBytes(message: SecureMessage): ByteArray = encodeFields(
        "UA-MESSAGE-SIGNATURE-v1", message.messageId, message.senderFingerprint,
        message.recipientFingerprint, message.createdAtEpochMillis, message.nonce, message.ciphertext
    )

    private fun encodeFields(vararg fields: Any): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            fields.forEach { field ->
                when (field) {
                    is String -> { val bytes = field.toByteArray(StandardCharsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
                    is Long -> output.writeLong(field)
                    is ByteArray -> { output.writeInt(field.size); output.write(field) }
                    else -> error("Unsupported signed field type.")
                }
            }
        }
        buffer.toByteArray()
    }
}