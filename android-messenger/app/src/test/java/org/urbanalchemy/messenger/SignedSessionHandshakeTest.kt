package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SignedSessionHandshakeTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun offer(sender: java.security.KeyPair, recipient: java.security.KeyPair): SignedSessionHandshake.Offer {
        val now = 1_800_000_000_000L
        return SignedSessionHandshake.createOffer(
            sessionId = "session-001",
            senderFingerprint = SignedSessionHandshake.fingerprint(sender.public),
            recipientFingerprint = SignedSessionHandshake.fingerprint(recipient.public),
            ephemeralKeyPair = keyPair(),
            signingKeyPair = sender,
            nonce = ByteArray(32) { it.toByte() },
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 60_000L
        )
    }

    @Test fun verifiesSignedOfferForPinnedPeerAndIntendedRecipient() {
        val alice = keyPair(); val bob = keyPair(); val offer = offer(alice, bob)
        val verified = SignedSessionHandshake.verifyOffer(
            offer, offer.senderFingerprint, offer.recipientFingerprint, alice.public,
            offer.issuedAtEpochMillis + 1_000L
        )
        assertEquals(offer.sessionId, verified.sessionId)
        assertArrayEquals(offer.ephemeralPublicKey, verified.ephemeralPublicKey.encoded)
    }

    @Test fun rejectsWrongPinnedSender() {
        val alice = keyPair(); val bob = keyPair(); val mallory = keyPair(); val offer = offer(alice, bob)
        try {
            SignedSessionHandshake.verifyOffer(offer, offer.senderFingerprint, offer.recipientFingerprint, mallory.public, offer.issuedAtEpochMillis)
            fail("Expected sender key rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsOfferAddressedToDifferentRecipient() {
        val alice = keyPair(); val bob = keyPair(); val mallory = keyPair(); val offer = offer(alice, bob)
        try {
            SignedSessionHandshake.verifyOffer(offer, offer.senderFingerprint, SignedSessionHandshake.fingerprint(mallory.public), alice.public, offer.issuedAtEpochMillis)
            fail("Expected recipient rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsExpiredOffer() {
        val alice = keyPair(); val bob = keyPair(); val offer = offer(alice, bob)
        try {
            SignedSessionHandshake.verifyOffer(offer, offer.senderFingerprint, offer.recipientFingerprint, alice.public, offer.expiresAtEpochMillis + 1)
            fail("Expected expiry rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsTamperedEphemeralKey() {
        val alice = keyPair(); val bob = keyPair(); val offer = offer(alice, bob)
        val tampered = offer.copy(ephemeralPublicKey = keyPair().public.encoded)
        try {
            SignedSessionHandshake.verifyOffer(tampered, offer.senderFingerprint, offer.recipientFingerprint, alice.public, offer.issuedAtEpochMillis)
            fail("Expected signature rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsExcessiveLifetime() {
        val alice = keyPair(); val bob = keyPair(); val now = 1_800_000_000_000L
        try {
            SignedSessionHandshake.createOffer(
                "too-long", SignedSessionHandshake.fingerprint(alice.public), SignedSessionHandshake.fingerprint(bob.public),
                keyPair(), alice, ByteArray(32), now, now + SignedSessionHandshake.DEFAULT_MAX_LIFETIME_MILLIS + 1
            ).also { SignedSessionHandshake.verifyOffer(it, it.senderFingerprint, it.recipientFingerprint, alice.public, now) }
            fail("Expected lifetime rejection")
        } catch (_: IllegalArgumentException) { }
    }
}