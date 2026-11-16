package org.urbanalchemy.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageDeliveryTest {
    private fun queued() = OutboxRecord(
        messageId = "message-001",
        recipientIdentity = "peer-fingerprint",
        payloadBase64 = "encrypted-payload",
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 10_000L
    )

    @Test fun routerAcceptanceIsNotDelivery() {
        val accepted = MessageDelivery.transition(queued(), DeliveryState.ROUTER_ACCEPTED)
        assertEquals(DeliveryState.ROUTER_ACCEPTED, accepted.state)
        assertFalse(accepted.state == DeliveryState.DELIVERED)
    }

    @Test fun deliveryRequiresMatchingAcknowledgement() {
        val sent = MessageDelivery.transition(
            MessageDelivery.transition(queued(), DeliveryState.ROUTER_ACCEPTED),
            DeliveryState.SENT
        )
        val ack = DeliveryAcknowledgement("message-001", "peer-fingerprint", 2_000L)
        assertEquals(DeliveryState.DELIVERED, MessageDelivery.acknowledge(sent, ack).state)
    }

    @Test fun acknowledgementForAnotherPeerIsRejected() {
        val sent = MessageDelivery.transition(
            MessageDelivery.transition(queued(), DeliveryState.ROUTER_ACCEPTED),
            DeliveryState.SENT
        )
        val ack = DeliveryAcknowledgement("message-001", "attacker-fingerprint", 2_000L)
        assertThrows(IllegalArgumentException::class.java) {
            MessageDelivery.acknowledge(sent, ack)
        }
    }

    @Test fun duplicateDeliveryEventIsIdempotent() {
        val delivered = MessageDelivery.transition(
            MessageDelivery.transition(
                MessageDelivery.transition(queued(), DeliveryState.ROUTER_ACCEPTED),
                DeliveryState.SENT
            ),
            DeliveryState.DELIVERED
        )
        assertEquals(delivered, MessageDelivery.transition(delivered, DeliveryState.DELIVERED))
    }

    @Test fun failedMessageCanBeRequeuedAndAttemptCountIncrements() {
        val failed = MessageDelivery.transition(queued(), DeliveryState.FAILED, "peer unavailable")
        val retried = MessageDelivery.transition(failed, DeliveryState.QUEUED)
        assertEquals(1, retried.attemptCount)
        assertEquals(null, retried.lastError)
        assertEquals(DeliveryState.QUEUED, retried.state)
    }

    @Test fun deliveredMessageCannotReturnToQueue() {
        val delivered = queued().copy(state = DeliveryState.DELIVERED)
        assertThrows(IllegalArgumentException::class.java) {
            MessageDelivery.transition(delivered, DeliveryState.QUEUED)
        }
    }

    @Test fun mismatchedMessageAcknowledgementIsRejected() {
        val sent = queued().copy(state = DeliveryState.SENT)
        val ack = DeliveryAcknowledgement("different-message", "peer-fingerprint", 2_000L)
        assertThrows(IllegalArgumentException::class.java) {
            MessageDelivery.acknowledge(sent, ack)
        }
    }
}
