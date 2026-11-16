package org.urbanalchemy.messenger

import java.security.KeyPair
import java.security.PublicKey

/**
 * Verifies both pinned-peer offers, consumes their replay IDs, and derives the
 * session key using only this device's ephemeral private key. The peer's
 * ephemeral private key is never available to or required by this device.
 */
class AuthenticatedSessionEstablisher(private val replayGuard: SessionReplayGuard) {

    class EstablishedSession internal constructor(
        val sessionId: String,
        val localFingerprint: String,
        val peerFingerprint: String,
        sessionKey: ByteArray
    ) : AutoCloseable {
        private val key = sessionKey.copyOf()
        @Volatile private var closed = false

        @Synchronized
        fun copySessionKey(): ByteArray {
            check(!closed) { "Session has been closed." }
            return key.copyOf()
        }

        @Synchronized
        override fun close() {
            if (!closed) {
                key.fill(0)
                closed = true
            }
        }
    }

    /**
     * Offers are ordered by role, not by local perspective. Both signing keys
     * must already be pinned to fingerprints verified out of band. Only the
     * local ephemeral key pair is accepted; the peer contributes a public key
     * in its signed offer.
     */
    fun establish(
        initiatorOffer: SignedSessionHandshake.Offer,
        responderOffer: SignedSessionHandshake.Offer,
        initiatorSigningKey: PublicKey,
        responderSigningKey: PublicKey,
        localEphemeralKeyPair: KeyPair,
        nowEpochMillis: Long,
        localIsInitiator: Boolean
    ): EstablishedSession {
        require(nowEpochMillis >= 0) { "Current time must not be negative." }
        val initiatorFingerprint = SignedSessionHandshake.fingerprint(initiatorSigningKey)
        val responderFingerprint = SignedSessionHandshake.fingerprint(responderSigningKey)
        require(initiatorFingerprint != responderFingerprint) { "A session requires two distinct peers." }

        val verifiedInitiator = SignedSessionHandshake.verifyOffer(
            initiatorOffer, initiatorFingerprint, responderFingerprint,
            initiatorSigningKey, nowEpochMillis
        )
        val verifiedResponder = SignedSessionHandshake.verifyOffer(
            responderOffer, responderFingerprint, initiatorFingerprint,
            responderSigningKey, nowEpochMillis
        )
        val transcript = SessionKeyAgreement.Transcript(verifiedInitiator, verifiedResponder)

        val localOffer = if (localIsInitiator) verifiedInitiator else verifiedResponder
        require(localOffer.ephemeralPublicKey.encoded.contentEquals(localEphemeralKeyPair.public.encoded)) {
            "Local ephemeral key does not match the local signed offer."
        }

        // Consume both signed offer IDs before deriving or returning a session key.
        require(replayGuard.recordAccepted(verifiedInitiator, nowEpochMillis)) {
            "Initiator session offer has already been accepted."
        }
        require(replayGuard.recordAccepted(verifiedResponder, nowEpochMillis)) {
            "Responder session offer has already been accepted."
        }

        val key = SessionKeyAgreement.deriveSessionKey(localEphemeralKeyPair, transcript, localIsInitiator)
        return try {
            EstablishedSession(
                sessionId = verifiedInitiator.sessionId,
                localFingerprint = if (localIsInitiator) initiatorFingerprint else responderFingerprint,
                peerFingerprint = if (localIsInitiator) responderFingerprint else initiatorFingerprint,
                sessionKey = key
            )
        } finally {
            key.fill(0)
        }
    }
}
