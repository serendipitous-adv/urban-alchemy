package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class SignedDeliveryAcknowledgementTest {
    private fun keys() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    @Test fun recipientCanSignAndVerifyReceipt() {
        val sender = keys()
        val recipient = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        val ack = SignedDeliveryAcknowledgement.sign(
            "m-1", senderId, recipientId, 1_500L, recipient.private
        )
        val verified = SignedDeliveryAcknowledgement.verify(
            ack, "m-1", senderId, recipientId, recipient.public, 2_000L, 1_000L
        )
        assertEquals("m-1", verified.messageId)
        assertEquals(recipientId, verified.recipientIdentity)
    }

    @Test fun tamperedReceiptIsRejected() {
        val sender = keys()
        val recipient = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        val ack = SignedDeliveryAcknowledgement.sign("m-2", senderId, recipientId, 1_500L, recipient.private)
        val tampered = ack.copy(messageId = "m-other")
        try {
            SignedDeliveryAcknowledgement.verify(tampered, "m-2", senderId, recipientId, recipient.public, 2_000L, 1_000L)
            fail("Expected tampered receipt rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun wrongSigningKeyIsRejected() {
        val sender = keys()
        val recipient = keys()
        val impostor = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        val ack = SignedDeliveryAcknowledgement.sign("m-3", senderId, recipientId, 1_500L, recipient.private)
        try {
            SignedDeliveryAcknowledgement.verify(ack, "m-3", senderId, recipientId, impostor.public, 2_000L, 1_000L)
            fail("Expected unpinned key rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun futureAndPredatingReceiptsAreRejected() {
        val sender = keys()
        val recipient = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        val ack = SignedDeliveryAcknowledgement.sign("m-4", senderId, recipientId, 2_500L, recipient.private)
        try {
            SignedDeliveryAcknowledgement.verify(ack, "m-4", senderId, recipientId, recipient.public, 2_000L, 1_000L)
            fail("Expected future receipt rejection")
        } catch (_: IllegalArgumentException) { }
        val old = SignedDeliveryAcknowledgement.sign("m-4", senderId, recipientId, 500L, recipient.private)
        try {
            SignedDeliveryAcknowledgement.verify(old, "m-4", senderId, recipientId, recipient.public, 2_000L, 1_000L)
            fail("Expected receipt predating message rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun wrongRecipientCannotProduceAcceptedReceipt() {
        val sender = keys()
        val recipient = keys()
        val other = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        val otherId = SignedSessionHandshake.fingerprint(other.public)
        val ack = SignedDeliveryAcknowledgement.sign("m-5", senderId, otherId, 1_500L, other.private)
        try {
            SignedDeliveryAcknowledgement.verify(ack, "m-5", senderId, recipientId, recipient.public, 2_000L, 1_000L)
            fail("Expected wrong recipient rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
