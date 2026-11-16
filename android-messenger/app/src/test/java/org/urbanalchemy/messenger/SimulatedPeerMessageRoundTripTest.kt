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
class SimulatedPeerMessageRoundTripTest {
    private lateinit var context: Context
    private lateinit var aliceSigning: KeyPair
    private lateinit var bobSigning: KeyPair
    private lateinit var aliceEphemeral: KeyPair
    private lateinit var bobEphemeral: KeyPair
    private lateinit var aliceSession: AuthenticatedSessionEstablisher.EstablishedSession
    private lateinit var bobSession: AuthenticatedSessionEstablisher.EstablishedSession
    private val databaseNames = mutableListOf<String>()
    private val now = 1_800_000_000_000L

    private class MemoryInbox : IncomingMessageStore {
        val messages = linkedMapOf<String, ReceivedMessage>()
        override fun insertIfAbsent(message: ReceivedMessage): Boolean {
            if (messages.containsKey(message.messageId)) return false
            messages[message.messageId] = message
            return true
        }
        override fun contains(messageId: String) = messages.containsKey(messageId)
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun replayGuard(label: String): SessionReplayGuard {
        val name = "sim-peer-$label-${System.nanoTime()}.db"
        databaseNames += name
        return SessionReplayGuard(context, name)
    }

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        aliceSigning = keyPair()
        bobSigning = keyPair()
        aliceEphemeral = keyPair()
        bobEphemeral = keyPair()
        val aliceId = SignedSessionHandshake.fingerprint(aliceSigning.public)
        val bobId = SignedSessionHandshake.fingerprint(bobSigning.public)
        val sessionId = "simulated-peer-session"
        val aliceOffer = SignedSessionHandshake.createOffer(
            sessionId, aliceId, bobId, aliceEphemeral, aliceSigning,
            ByteArray(32) { (it + 3).toByte() }, now, now + 60_000L
        )
        val bobOffer = SignedSessionHandshake.createOffer(
            sessionId, bobId, aliceId, bobEphemeral, bobSigning,
            ByteArray(32) { (it + 71).toByte() }, now, now + 60_000L
        )

        // The peers exchange only public signed offers. Each side retains its own private key.
        val aliceReceivedOffer = SignedSessionOfferCodec.decode(SignedSessionOfferCodec.encode(bobOffer))
        val bobReceivedOffer = SignedSessionOfferCodec.decode(SignedSessionOfferCodec.encode(aliceOffer))
        val aliceOwnOffer = SignedSessionOfferCodec.decode(SignedSessionOfferCodec.encode(aliceOffer))
        val bobOwnOffer = SignedSessionOfferCodec.decode(SignedSessionOfferCodec.encode(bobOffer))

        aliceSession = AuthenticatedSessionEstablisher(replayGuard("alice")).establish(
            aliceOwnOffer, aliceReceivedOffer, aliceSigning.public, bobSigning.public,
            aliceEphemeral, now + 1_000L, true
        )
        bobSession = AuthenticatedSessionEstablisher(replayGuard("bob")).establish(
            bobReceivedOffer, aliceReceivedOffer, aliceSigning.public, bobSigning.public,
            bobEphemeral, now + 1_000L, false
        )
    }

    @After fun tearDown() {
        aliceSession.close()
        bobSession.close()
        databaseNames.forEach(context::deleteDatabase)
    }

    private fun exchange(
        senderSession: AuthenticatedSessionEstablisher.EstablishedSession,
        recipientSession: AuthenticatedSessionEstablisher.EstablishedSession,
        senderSigning: KeyPair,
        recipientSigning: KeyPair,
        senderInbox: MemoryInbox,
        senderIsAlice: Boolean,
        messageId: String,
        body: String
    ) {
        val senderKey = senderSession.copySessionKey()
        val recipientKey = recipientSession.copySessionKey()
        try {
            assertArrayEquals(senderKey, recipientKey)
            val createdAt = now + 2_000L
            val encrypted = SessionMessageCrypto.encrypt(
                senderKey, body, messageId, createdAt,
                senderSession.localFingerprint, senderSession.peerFingerprint,
                senderSigning.private
            )
            val envelope = EncryptedMessageEnvelope.fromCryptoMessage(encrypted, now + 30_000L)
            val frame = UaProtocolFrame(UaProtocolFrame.Type.ENCRYPTED_MESSAGE, envelope.encodeBase64().toByteArray())
            val decodedFrame = UaProtocolFrame.decode(frame.encode())
            assertEquals(UaProtocolFrame.Type.ENCRYPTED_MESSAGE, decodedFrame.type)

            val receiver = AuthenticatedMessageReceiver(senderInbox)
            assertEquals(
                AuthenticatedMessageReceiver.Result.STORED,
                receiver.receive(
                    String(decodedFrame.payload), recipientKey,
                    senderSession.localFingerprint, recipientSession.localFingerprint,
                    senderSigning.public, now + 3_000L
                )
            )
            assertEquals(body, senderInbox.messages[messageId]?.text)

            val signedAck = SignedDeliveryAcknowledgement.sign(
                messageId, senderSession.localFingerprint, recipientSession.localFingerprint,
                now + 4_000L, recipientSigning.private
            )
            val ackFrame = UaProtocolFrame(
                UaProtocolFrame.Type.DELIVERY_ACKNOWLEDGEMENT,
                SignedDeliveryAcknowledgementCodec.encode(signedAck)
            )
            val decodedAckFrame = UaProtocolFrame.decode(ackFrame.encode())
            val decodedAck = SignedDeliveryAcknowledgementCodec.decode(decodedAckFrame.payload)
            val verified = SignedDeliveryAcknowledgement.verify(
                decodedAck, messageId, senderSession.localFingerprint,
                recipientSession.localFingerprint, recipientSigning.public,
                now + 5_000L, createdAt
            )
            assertEquals(messageId, verified.messageId)
        } finally {
            senderKey.fill(0)
            recipientKey.fill(0)
        }
    }

    @Test fun twoSimulatedPeersExchangeEncryptedMessagesAndVerifiedReceiptsBothWays() {
        val aliceInbox = MemoryInbox()
        val bobInbox = MemoryInbox()
        exchange(
            aliceSession, bobSession, aliceSigning, bobSigning,
            bobInbox, true, "alice-to-bob-001", "Hello Bob, offline."
        )
        exchange(
            bobSession, aliceSession, bobSigning, aliceSigning,
            aliceInbox, false, "bob-to-alice-001", "Hello Alice, received."
        )
        assertEquals(1, bobInbox.messages.size)
        assertEquals(1, aliceInbox.messages.size)
    }
}
