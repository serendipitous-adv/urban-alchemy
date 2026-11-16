package org.urbanalchemy.messenger

import android.content.Context
import android.content.ContextWrapper
import java.security.KeyPairGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VerifiedDeliveryAcknowledgementTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var store: PersistentOutbox

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        databaseName = "verified-ack-${System.nanoTime()}.db"
        store = PersistentOutbox(object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = super.getDatabasePath(databaseName)
        })
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    private fun keys() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun sentRecord(senderId: String, recipientId: String) {
        store.enqueue(OutboxRecord(
            messageId = "wire-message-1",
            recipientIdentity = recipientId,
            payloadBase64 = "encrypted-envelope",
            createdAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 60_000L
        ))
        store.transition("wire-message-1", DeliveryState.ROUTER_ACCEPTED)
        store.transition("wire-message-1", DeliveryState.SENT)
    }

    @Test fun validWireReceiptIsVerifiedAndAtomicallyMarksMessageDelivered() {
        val sender = keys()
        val recipient = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        sentRecord(senderId, recipientId)
        val acknowledgement = SignedDeliveryAcknowledgement.sign(
            "wire-message-1", senderId, recipientId, 1_500L, recipient.private
        )
        val encoded = SignedDeliveryAcknowledgementCodec.encodeBase64(acknowledgement)

        val result = store.acknowledgeVerifiedBase64(encoded, senderId, recipient.public, 2_000L)

        assertEquals(DeliveryState.DELIVERED, result.state)
        assertEquals(DeliveryState.DELIVERED, store.list().single().state)
    }

    @Test fun invalidSignatureCannotChangeSentState() {
        val sender = keys()
        val recipient = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        sentRecord(senderId, recipientId)
        val valid = SignedDeliveryAcknowledgement.sign(
            "wire-message-1", senderId, recipientId, 1_500L, recipient.private
        )
        val damagedSignature = valid.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val encoded = SignedDeliveryAcknowledgementCodec.encodeBase64(valid.copy(signature = damagedSignature))

        try {
            store.acknowledgeVerifiedBase64(encoded, senderId, recipient.public, 2_000L)
            fail("Expected invalid signature rejection")
        } catch (_: IllegalArgumentException) { }

        assertEquals(DeliveryState.SENT, store.list().single().state)
    }

    @Test fun unpinnedRecipientKeyCannotMarkMessageDelivered() {
        val sender = keys()
        val recipient = keys()
        val impostor = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        sentRecord(senderId, recipientId)
        val acknowledgement = SignedDeliveryAcknowledgement.sign(
            "wire-message-1", senderId, recipientId, 1_500L, recipient.private
        )
        val encoded = SignedDeliveryAcknowledgementCodec.encodeBase64(acknowledgement)

        try {
            store.acknowledgeVerifiedBase64(encoded, senderId, impostor.public, 2_000L)
            fail("Expected unpinned key rejection")
        } catch (_: IllegalArgumentException) { }

        assertEquals(DeliveryState.SENT, store.list().single().state)
    }

    @Test fun malformedWireReceiptCannotChangeSentState() {
        val sender = keys()
        val recipient = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        sentRecord(senderId, recipientId)

        try {
            store.acknowledgeVerifiedBase64("not-a-receipt", senderId, recipient.public, 2_000L)
            fail("Expected malformed receipt rejection")
        } catch (_: IllegalArgumentException) { }

        assertEquals(DeliveryState.SENT, store.list().single().state)
    }
}
