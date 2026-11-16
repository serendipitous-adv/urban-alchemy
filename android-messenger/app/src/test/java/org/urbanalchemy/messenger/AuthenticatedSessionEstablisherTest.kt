package org.urbanalchemy.messenger

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthenticatedSessionEstablisherTest {
    private lateinit var context: Context
    private lateinit var alice: KeyPair
    private lateinit var bob: KeyPair
    private lateinit var aliceEphemeral: KeyPair
    private lateinit var bobEphemeral: KeyPair
    private lateinit var aliceOffer: SignedSessionHandshake.Offer
    private lateinit var bobOffer: SignedSessionHandshake.Offer
    private val now = 1_800_000_000_000L
    private val dbNames = mutableListOf<String>()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun guard(): SessionReplayGuard {
        val name = "session-establish-${System.nanoTime()}.db"
        dbNames += name
        return SessionReplayGuard(context, name)
    }

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        alice = keyPair(); bob = keyPair()
        aliceEphemeral = keyPair(); bobEphemeral = keyPair()
        val aliceId = SignedSessionHandshake.fingerprint(alice.public)
        val bobId = SignedSessionHandshake.fingerprint(bob.public)
        aliceOffer = SignedSessionHandshake.createOffer(
            "session-accept-1", aliceId, bobId, aliceEphemeral, alice,
            ByteArray(32) { (it + 1).toByte() }, now, now + 60_000L
        )
        bobOffer = SignedSessionHandshake.createOffer(
            "session-accept-1", bobId, aliceId, bobEphemeral, bob,
            ByteArray(32) { (it + 41).toByte() }, now, now + 60_000L
        )
    }

    @After fun tearDown() { dbNames.forEach(context::deleteDatabase) }

    private fun verifiedAlice() = SignedSessionHandshake.verifyOffer(
        aliceOffer, aliceOffer.senderFingerprint, aliceOffer.recipientFingerprint,
        alice.public, now + 1_000L
    )
    private fun verifiedBob() = SignedSessionHandshake.verifyOffer(
        bobOffer, bobOffer.senderFingerprint, bobOffer.recipientFingerprint,
        bob.public, now + 1_000L
    )

    @Test fun bothPeersEstablishSameKeyUsingOnlyTheirOwnEphemeralPrivateKey() {
        val aliceSession = AuthenticatedSessionEstablisher(guard()).establish(
            aliceOffer, bobOffer, alice.public, bob.public, aliceEphemeral, now + 1_000L, true
        )
        val bobSession = AuthenticatedSessionEstablisher(guard()).establish(
            aliceOffer, bobOffer, alice.public, bob.public, bobEphemeral, now + 1_000L, false
        )
        val aliceKey = aliceSession.copySessionKey()
        val bobKey = bobSession.copySessionKey()
        assertArrayEquals(aliceKey, bobKey)
        assertEquals(SignedSessionHandshake.fingerprint(bob.public), aliceSession.peerFingerprint)
        aliceKey.fill(0); bobKey.fill(0)
        aliceSession.close(); bobSession.close()
        try { aliceSession.copySessionKey(); fail("Closed session must not reveal its key.") }
        catch (_: IllegalStateException) { }
    }

    @Test fun replayedOfferPairIsRejected() {
        val establisher = AuthenticatedSessionEstablisher(guard())
        val first = establisher.establish(
            aliceOffer, bobOffer, alice.public, bob.public, aliceEphemeral, now + 1_000L, true
        )
        first.close()
        try {
            establisher.establish(
                aliceOffer, bobOffer, alice.public, bob.public, aliceEphemeral, now + 1_000L, true
            )
            fail("Previously accepted offers must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun wrongLocalEphemeralKeyIsRejected() {
        try {
            AuthenticatedSessionEstablisher(guard()).establish(
                aliceOffer, bobOffer, alice.public, bob.public, keyPair(), now + 1_000L, true
            )
            fail("Mismatched local ephemeral key must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun tamperedOfferIsRejectedBeforeReplayGuardMutation() {
        val tampered = aliceOffer.copy(nonce = ByteArray(32) { 99 })
        val replay = guard()
        try {
            AuthenticatedSessionEstablisher(replay).establish(
                tampered, bobOffer, alice.public, bob.public, aliceEphemeral, now + 1_000L, true
            )
            fail("Tampered offer must be rejected.")
        } catch (_: IllegalArgumentException) { }
        assertTrue(replay.recordAccepted(verifiedAlice(), now + 1_000L))
        replay.close()
    }

    @Test fun pairAcceptanceRollsBackFirstOfferWhenSecondWasAlreadyAccepted() {
        val replay = guard()
        assertTrue(replay.recordAccepted(verifiedBob(), now + 1_000L))
        assertFalse(replay.recordAcceptedPair(verifiedAlice(), verifiedBob(), now + 1_000L))
        // The first insert from the failed pair must have rolled back.
        assertTrue(replay.recordAccepted(verifiedAlice(), now + 1_000L))
        replay.close()
    }
}
