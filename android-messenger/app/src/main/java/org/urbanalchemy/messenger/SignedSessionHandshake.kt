package org.urbanalchemy.messenger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Signed, short-lived offer for an ephemeral P-256 ECDH session key.
 * The long-lived signing key authenticates the offer; the ephemeral private
 * key remains with the caller and must be discarded after session teardown.
 *
 * This class deliberately does not claim replay prevention: callers must keep
 * a persistent/recent cache of accepted (senderFingerprint, sessionId) pairs.
 * The expected sender key must already be pinned through out-of-band
 * fingerprint verification; a self-signed offer alone proves no human identity.
 */
object SignedSessionHandshake {
    private const val DOMAIN = "UA-SESSION-OFFER-v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val KEY_ALGORITHM = "EC"
    const val DEFAULT_MAX_LIFETIME_MILLIS = 5 * 60 * 1000L
    private const val MAX_NONCE_BYTES = 64

    data class Offer(
        val sessionId: String,
        val senderFingerprint: String,
        val recipientFingerprint: String,
        val ephemeralPublicKey: ByteArray,
        val nonce: ByteArray,
        val issuedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
        val signature: ByteArray
    )

    data class VerifiedOffer(
        val sessionId: String,
        val senderFingerprint: String,
        val recipientFingerprint: String,
        val ephemeralPublicKey: PublicKey,
        val nonce: ByteArray,
        val issuedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long
    )

    fun createOffer(
        sessionId: String,
        senderFingerprint: String,
        recipientFingerprint: String,
        ephemeralKeyPair: KeyPair,
        signingKeyPair: KeyPair,
        nonce: ByteArray,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long
    ): Offer {
        require(sessionId.isNotBlank()) { "Session ID is required." }
        require(senderFingerprint.isNotBlank() && recipientFingerprint.isNotBlank()) { "Both peer fingerprints are required." }
        require(senderFingerprint != recipientFingerprint) { "A session must have two distinct peers." }
        require(nonce.size in 16..MAX_NONCE_BYTES) { "Nonce must contain 16 to 64 bytes." }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Expiry must be after issue time." }
        require(fingerprint(signingKeyPair.public) == senderFingerprint) { "Signing key does not match sender fingerprint." }
        require(ephemeralKeyPair.public.algorithm.equals(KEY_ALGORITHM, ignoreCase = true)) { "Ephemeral key must be an EC key." }
        val unsigned = Offer(sessionId, senderFingerprint, recipientFingerprint, ephemeralKeyPair.public.encoded, nonce.copyOf(), issuedAtEpochMillis, expiresAtEpochMillis, byteArrayOf())
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(signingKeyPair.private)
        signer.update(canonicalBytes(unsigned))
        return unsigned.copy(signature = signer.sign())
    }

    fun verifyOffer(
        offer: Offer,
        expectedSenderFingerprint: String,
        expectedRecipientFingerprint: String,
        pinnedSenderSigningKey: PublicKey,
        nowEpochMillis: Long,
        maxLifetimeMillis: Long = DEFAULT_MAX_LIFETIME_MILLIS
    ): VerifiedOffer {
        require(offer.senderFingerprint == expectedSenderFingerprint) { "Offer sender does not match the pinned peer." }
        require(offer.recipientFingerprint == expectedRecipientFingerprint) { "Offer is addressed to another identity." }
        require(fingerprint(pinnedSenderSigningKey) == expectedSenderFingerprint) { "Pinned signing key fingerprint is invalid." }
        require(offer.sessionId.isNotBlank()) { "Session ID is required." }
        require(offer.nonce.size in 16..MAX_NONCE_BYTES) { "Invalid session nonce." }
        require(offer.issuedAtEpochMillis >= 0 && offer.expiresAtEpochMillis > offer.issuedAtEpochMillis) { "Invalid offer lifetime." }
        require(maxLifetimeMillis > 0 && offer.expiresAtEpochMillis - offer.issuedAtEpochMillis <= maxLifetimeMillis) { "Offer lifetime exceeds policy." }
        require(nowEpochMillis >= offer.issuedAtEpochMillis - 30_000L) { "Offer is not yet valid." }
        require(nowEpochMillis <= offer.expiresAtEpochMillis) { "Offer has expired." }
        val ephemeral = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(offer.ephemeralPublicKey))
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(pinnedSenderSigningKey)
        verifier.update(canonicalBytes(offer))
        require(verifier.verify(offer.signature)) { "Session offer signature is invalid." }
        return VerifiedOffer(offer.sessionId, offer.senderFingerprint, offer.recipientFingerprint, ephemeral, offer.nonce.copyOf(), offer.issuedAtEpochMillis, offer.expiresAtEpochMillis)
    }

    fun fingerprint(publicKey: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey.encoded).take(16).joinToString(":") { "%02x".format(it) }

    private fun canonicalBytes(offer: Offer): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            writeString(out, DOMAIN)
            writeString(out, offer.sessionId)
            writeString(out, offer.senderFingerprint)
            writeString(out, offer.recipientFingerprint)
            writeBytes(out, offer.ephemeralPublicKey)
            writeBytes(out, offer.nonce)
            out.writeLong(offer.issuedAtEpochMillis)
            out.writeLong(offer.expiresAtEpochMillis)
        }
        buffer.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) = writeBytes(out, value.toByteArray(StandardCharsets.UTF_8))
    private fun writeBytes(out: DataOutputStream, value: ByteArray) { out.writeInt(value.size); out.write(value) }
}