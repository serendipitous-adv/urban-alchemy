package org.urbanalchemy.messenger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.KeyAgreement
import java.security.KeyPair
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Derives the same session key on both sides from the two signed ephemeral offers. */
object SessionKeyAgreement {
    private const val DOMAIN = "UA-ECDH-SESSION-KEY-v1"
    private const val MAC_ALGORITHM = "HmacSHA256"

    data class Transcript(
        val initiatorOffer: SignedSessionHandshake.VerifiedOffer,
        val responderOffer: SignedSessionHandshake.VerifiedOffer
    ) {
        init {
            require(initiatorOffer.sessionId == responderOffer.sessionId) { "Session IDs do not match." }
            require(initiatorOffer.senderFingerprint == responderOffer.recipientFingerprint) { "Responder offer is not addressed to the initiator." }
            require(responderOffer.senderFingerprint == initiatorOffer.recipientFingerprint) { "Initiator offer is not addressed to the responder." }
            require(!initiatorOffer.nonce.contentEquals(responderOffer.nonce)) { "Session nonces must be distinct." }
            require(!initiatorOffer.ephemeralPublicKey.encoded.contentEquals(responderOffer.ephemeralPublicKey.encoded)) { "Ephemeral keys must be distinct." }
        }
    }

    /**
     * Returns a 32-byte key. The caller must keep it only for the session and
     * overwrite the returned byte array when the session ends.
     */
    fun deriveSessionKey(
        localEphemeralKeyPair: KeyPair,
        transcript: Transcript,
        localIsInitiator: Boolean
    ): ByteArray {
        val localOffer = if (localIsInitiator) transcript.initiatorOffer else transcript.responderOffer
        val peerOffer = if (localIsInitiator) transcript.responderOffer else transcript.initiatorOffer
        require(localOffer.ephemeralPublicKey.encoded.contentEquals(localEphemeralKeyPair.public.encoded)) {
            "Local ephemeral private key does not match the signed transcript."
        }
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localEphemeralKeyPair.private)
        agreement.doPhase(peerOffer.ephemeralPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        val context = canonicalTranscript(transcript)
        val salt = MessageDigest.getInstance("SHA-256").digest(context)
        var pseudorandomKey: ByteArray? = null
        try {
            val extract = Mac.getInstance(MAC_ALGORITHM)
            extract.init(SecretKeySpec(salt, MAC_ALGORITHM))
            pseudorandomKey = extract.doFinal(sharedSecret)
            val expand = Mac.getInstance(MAC_ALGORITHM)
            expand.init(SecretKeySpec(pseudorandomKey, MAC_ALGORITHM))
            expand.update(DOMAIN.toByteArray(StandardCharsets.UTF_8))
            expand.update(1.toByte())
            return expand.doFinal().copyOf(32)
        } finally {
            sharedSecret.fill(0)
            context.fill(0)
            salt.fill(0)
            pseudorandomKey?.fill(0)
        }
    }

    private fun canonicalTranscript(transcript: Transcript): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            writeString(out, DOMAIN)
            val first = transcript.initiatorOffer
            val second = transcript.responderOffer
            writeString(out, first.sessionId)
            writeString(out, first.senderFingerprint)
            writeString(out, first.recipientFingerprint)
            writeBytes(out, first.ephemeralPublicKey.encoded)
            writeBytes(out, first.nonce)
            out.writeLong(first.issuedAtEpochMillis)
            out.writeLong(first.expiresAtEpochMillis)
            writeBytes(out, second.ephemeralPublicKey.encoded)
            writeBytes(out, second.nonce)
            out.writeLong(second.issuedAtEpochMillis)
            out.writeLong(second.expiresAtEpochMillis)
        }
        buffer.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) = writeBytes(out, value.toByteArray(StandardCharsets.UTF_8))
    private fun writeBytes(out: DataOutputStream, value: ByteArray) { out.writeInt(value.size); out.write(value) }
}