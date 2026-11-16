package org.urbanalchemy.messenger

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionKeyAgreementTest {
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private data class Peers(
        val alice: KeyPair, val bob: KeyPair,
        val aliceEphemeral: KeyPair, val bobEphemeral: KeyPair,
        val transcript: SessionKeyAgreement.Transcript
    )

    private fun peers(): Peers {
        val alice = keyPair(); val bob = keyPair()
        val aliceEphemeral = keyPair(); val bobEphemeral = keyPair()
        val aliceFingerprint = SignedSessionHandshake.fingerprint(alice.public)
        val bobFingerprint = SignedSessionHandshake.fingerprint(bob.public)
        val now = 1_800_000_000_000L
        val aliceOffer = SignedSessionHandshake.createOffer(
            "session-42", aliceFingerprint, bobFingerprint, aliceEphemeral, alice,
            ByteArray(32) { (it + 1).toByte() }, now, now + 60_000L
        )
        val bobOffer = SignedSessionHandshake.createOffer(
            "session-42", bobFingerprint, aliceFingerprint, bobEphemeral, bob,
            ByteArray(32) { (it + 51).toByte() }, now, now + 60_000L
        )
        val verifiedAlice = SignedSessionHandshake.verifyOffer(aliceOffer, aliceFingerprint, bobFingerprint, alice.public, now)
        val verifiedBob = SignedSessionHandshake.verifyOffer(bobOffer, bobFingerprint, aliceFingerprint, bob.public, now)
        return Peers(alice, bob, aliceEphemeral, bobEphemeral, SessionKeyAgreement.Transcript(verifiedAlice, verifiedBob))
    }

    @Test fun bothPeersDeriveIdenticalSessionKey() {
        val p = peers()
        val aliceKey = SessionKeyAgreement.deriveSessionKey(p.aliceEphemeral, p.transcript, true)
        val bobKey = SessionKeyAgreement.deriveSessionKey(p.bobEphemeral, p.transcript, false)
        assertArrayEquals(aliceKey, bobKey)
        assertEquals(32, aliceKey.size)
        aliceKey.fill(0); bobKey.fill(0)
    }

    @Test fun wrongLocalEphemeralKeyIsRejected() {
        val p = peers()
        try {
            SessionKeyAgreement.deriveSessionKey(keyPair(), p.transcript, true)
            fail("Expected local key mismatch")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun mismatchedSessionIdsAreRejected() {
        val p = peers()
        val other = p.transcript.responderOffer.copy(sessionId = "different")
        try {
            SessionKeyAgreement.Transcript(p.transcript.initiatorOffer, other)
            fail("Expected transcript mismatch")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun replayGuardPersistsAcceptedOffersAndScopesBySender() {
        val p = peers()
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val dbName = "session-replay-test-${System.nanoTime()}.db"
        val now = p.transcript.initiatorOffer.issuedAtEpochMillis + 1
        val guard = SessionReplayGuard(context, dbName)
        assertTrue(guard.recordAccepted(p.transcript.initiatorOffer, now))
        assertFalse(guard.recordAccepted(p.transcript.initiatorOffer, now))
        guard.close()
        val reopened = SessionReplayGuard(context, dbName)
        assertFalse(reopened.recordAccepted(p.transcript.initiatorOffer, now))
        assertTrue(reopened.recordAccepted(p.transcript.responderOffer, now))
        reopened.close()
        context.deleteDatabase(dbName)
    }
}