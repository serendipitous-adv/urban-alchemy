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
class PeerSessionHandshakeControllerTest {
    private lateinit var context: Context
    private lateinit var alice: KeyPair
    private lateinit var bob: KeyPair
    private lateinit var aliceController: PeerSessionHandshakeController
    private lateinit var bobController: PeerSessionHandshakeController
    private var aliceSession: AuthenticatedSessionEstablisher.EstablishedSession? = null
    private var bobSession: AuthenticatedSessionEstablisher.EstablishedSession? = null
    private val dbNames = mutableListOf<String>()
    private val now = 1_800_000_000_000L
    private val errors = mutableListOf<String>()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun guard(label: String): SessionReplayGuard {
        val name = "peer-handshake-$label-${System.nanoTime()}.db"
        dbNames += name
        return SessionReplayGuard(context, name)
    }

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        alice = keyPair()
        bob = keyPair()
        lateinit var aliceRef: PeerSessionHandshakeController
        lateinit var bobRef: PeerSessionHandshakeController
        aliceRef = PeerSessionHandshakeController(
            alice, bob.public, guard("alice"),
            sendFrame = { bobRef.receiveFrame(it) },
            onEstablished = { aliceSession = it },
            onError = { errors += "Alice: $it" },
            nowMillis = { now }
        )
        bobRef = PeerSessionHandshakeController(
            bob, alice.public, guard("bob"),
            sendFrame = { aliceRef.receiveFrame(it) },
            onEstablished = { bobSession = it },
            onError = { errors += "Bob: $it" },
            nowMillis = { now }
        )
        aliceController = aliceRef
        bobController = bobRef
    }

    @After fun tearDown() {
        aliceController.close()
        bobController.close()
        dbNames.forEach(context::deleteDatabase)
    }

    @Test fun initiatorAndResponderCompleteSignedHandshakeOverExchangedFrames() {
        aliceController.begin()
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(PeerSessionHandshakeController.State.ESTABLISHED, aliceController.state())
        assertEquals(PeerSessionHandshakeController.State.ESTABLISHED, bobController.state())
        val aliceKey = requireNotNull(aliceSession).copySessionKey()
        val bobKey = requireNotNull(bobSession).copySessionKey()
        assertArrayEquals(aliceKey, bobKey)
        aliceKey.fill(0); bobKey.fill(0)
        assertEquals(SignedSessionHandshake.fingerprint(bob.public), aliceSession?.peerFingerprint)
        assertEquals(SignedSessionHandshake.fingerprint(alice.public), bobSession?.peerFingerprint)
    }

    @Test fun wrongPinnedPeerKeyCannotEstablishSession() {
        val impostor = keyPair()
        val isolatedErrors = mutableListOf<String>()
        val controller = PeerSessionHandshakeController(
            alice, impostor.public, guard("impostor"),
            sendFrame = { },
            onEstablished = { fail("Unpinned identity must not establish.") },
            onError = { isolatedErrors += it },
            nowMillis = { now }
        )
        controller.begin()
        val fakeOffer = SignedSessionHandshake.createOffer(
            "wrong-peer-session",
            SignedSessionHandshake.fingerprint(bob.public),
            SignedSessionHandshake.fingerprint(alice.public),
            keyPair(), bob, ByteArray(32) { 7 }, now, now + 30_000L
        )
        controller.receiveFrame(
            UaProtocolFrame(
                UaProtocolFrame.Type.SESSION_OFFER,
                SignedSessionOfferCodec.encode(fakeOffer)
            ).encode()
        )
        assertEquals(PeerSessionHandshakeController.State.FAILED, controller.state())
        assertTrue(isolatedErrors.isNotEmpty())
        controller.close()
    }
}
