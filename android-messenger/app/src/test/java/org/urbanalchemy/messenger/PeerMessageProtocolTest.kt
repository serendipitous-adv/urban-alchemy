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
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PeerMessageProtocolTest {
    private lateinit var context: Context
    private lateinit var aliceSigning: KeyPair
    private lateinit var bobSigning: KeyPair
    private lateinit var aliceSession: AuthenticatedSessionEstablisher.EstablishedSession
    private lateinit var bobSession: AuthenticatedSessionEstablisher.EstablishedSession
    private lateinit var aliceProtocol: PeerMessageProtocol
    private lateinit var bobProtocol: PeerMessageProtocol
    private lateinit var outbox: PersistentOutbox
    private val aliceToBob = mutableListOf<ByteArray>()
    private val bobToAlice = mutableListOf<ByteArray>()
    private val aliceInbox = MemoryInbox()
    private val bobInbox = MemoryInbox()
    private val dbNames = mutableListOf<String>()
    private val now = 1_800_000_000_000L

    private class MemoryInbox : IncomingMessageStore {
        private val records = linkedMapOf<String, ReceivedMessage>()
        override fun insertIfAbsent(message: ReceivedMessage): Boolean {
            if (records.containsKey(message.messageId)) return false
            records[message.messageId] = message
            return true
        }
        override fun contains(messageId: String) = records.containsKey(messageId)
        override fun get(messageId: String) = records[messageId]
        fun size() = records.size
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun guard(label: String): SessionReplayGuard {
        val name = "peer-protocol-$label-${System.nanoTime()}.db"
        dbNames += name
        return SessionReplayGuard(context, name)
    }

    @Before fun setUp() {
        val localQueueKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        LocalMessageCrypto.keyProvider = { localQueueKey }
        context = org.robolectric.RuntimeEnvironment.getApplication()
        context.deleteDatabase("ua_transport_outbox.db")
        aliceSigning = keyPair()
        bobSigning = keyPair()
        val aliceId = SignedSessionHandshake.fingerprint(aliceSigning.public)
        val bobId = SignedSessionHandshake.fingerprint(bobSigning.public)
        val aliceEphemeral = keyPair()
        val bobEphemeral = keyPair()
        val sessionId = "peer-message-pipeline"
        val aliceOffer = SignedSessionHandshake.createOffer(
            sessionId, aliceId, bobId, aliceEphemeral, aliceSigning,
            ByteArray(32) { (it + 2).toByte() }, now, now + 60_000L
        )
        val bobOffer = SignedSessionHandshake.createOffer(
            sessionId, bobId, aliceId, bobEphemeral, bobSigning,
            ByteArray(32) { (it + 42).toByte() }, now, now + 60_000L
        )
        aliceSession = AuthenticatedSessionEstablisher(guard("alice")).establish(
            aliceOffer, bobOffer, aliceSigning.public, bobSigning.public,
            aliceEphemeral, now + 1_000L, true
        )
        bobSession = AuthenticatedSessionEstablisher(guard("bob")).establish(
            aliceOffer, bobOffer, aliceSigning.public, bobSigning.public,
            bobEphemeral, now + 1_000L, false
        )
        outbox = PersistentOutbox(context)
        aliceProtocol = PeerMessageProtocol(
            context, aliceSession, aliceSigning, bobSigning.public,
            sendFrame = { aliceToBob += it }, outbox = outbox, inbox = aliceInbox,
            nowMillis = { now }
        )
        bobProtocol = PeerMessageProtocol(
            context, bobSession, bobSigning, aliceSigning.public,
            sendFrame = { bobToAlice += it }, outbox = outbox, inbox = bobInbox,
            nowMillis = { now }
        )
    }

    @After fun tearDown() {
        aliceProtocol.close()
        // Alice protocol closes shared test outbox; Bob session is closed explicitly.
        bobSession.close()
        dbNames.forEach(context::deleteDatabase)
        context.deleteDatabase("ua_transport_outbox.db")
        LocalMessageCrypto.keyProvider = { throw IllegalStateException("Android Keystore is unavailable in plain JVM tests.") }
    }

    @Test fun encryptedMessagesAndSignedReceiptsFlowBothDirections() {
        val aliceId = aliceProtocol.queueMessage("Hello Bob, offline.", "alice-message-1")
        val locallyStored = outbox.list().first { it.messageId == aliceId }
        assertEquals("Hello Bob, offline.", LocalQueuedMessageCodec.open(locallyStored.payloadBase64))
        assertEquals(1, aliceProtocol.flushQueued())
        assertEquals(DeliveryState.SENT, outbox.list().first { it.messageId == aliceId }.state)
        bobProtocol.receiveFrame(aliceToBob.removeAt(0))
        assertEquals("Hello Bob, offline.", bobInbox.get(aliceId)?.text)
        assertEquals(1, bobToAlice.size)
        aliceProtocol.receiveFrame(bobToAlice.removeAt(0))
        assertEquals(DeliveryState.DELIVERED, outbox.list().first { it.messageId == aliceId }.state)

        val bobId = bobProtocol.queueMessage("Hello Alice, received.", "bob-message-1")
        assertEquals(1, bobProtocol.flushQueued())
        aliceProtocol.receiveFrame(bobToAlice.removeAt(0))
        assertEquals("Hello Alice, received.", aliceInbox.get(bobId)?.text)
        bobProtocol.receiveFrame(aliceToBob.removeAt(0))
        assertEquals(DeliveryState.DELIVERED, outbox.list().first { it.messageId == bobId }.state)
    }

    @Test fun duplicateInboundMessageIsStoredOnceAndStillReceivesAnotherReceipt() {
        val id = aliceProtocol.queueMessage("Deduplicate this.", "duplicate-message")
        aliceProtocol.flushQueued()
        val frame = aliceToBob.removeAt(0)
        bobProtocol.receiveFrame(frame)
        val firstReceiptCount = bobToAlice.size
        bobProtocol.receiveFrame(frame)
        assertEquals(1, bobInbox.size())
        assertEquals(firstReceiptCount + 1, bobToAlice.size)
        assertEquals(DeliveryState.SENT, outbox.list().first { it.messageId == id }.state)
    }

}
