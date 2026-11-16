package org.urbanalchemy.messenger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class EncryptedMessageEnvelopeTest {
    private fun sample() = EncryptedMessageEnvelope(
        messageId = "message-123",
        senderIdentity = "sender-fingerprint",
        recipientIdentity = "recipient-fingerprint",
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 60_000L,
        nonce = ByteArray(12) { it.toByte() },
        ciphertext = byteArrayOf(10, 20, 30, 40, 50)
    )

    @Test fun binaryEnvelopeRoundTripsWithoutChangingFields() {
        val original = sample()
        val decoded = EncryptedMessageEnvelope.decodeBase64(original.encodeBase64())
        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.senderIdentity, decoded.senderIdentity)
        assertEquals(original.recipientIdentity, decoded.recipientIdentity)
        assertEquals(original.createdAtEpochMillis, decoded.createdAtEpochMillis)
        assertEquals(original.expiresAtEpochMillis, decoded.expiresAtEpochMillis)
        assertArrayEquals(original.nonce, decoded.nonce)
        assertArrayEquals(original.ciphertext, decoded.ciphertext)
    }

    @Test fun envelopeCanCreateQueuedOutboxRecord() {
        val record = sample().toOutboxRecord()
        assertEquals("message-123", record.messageId)
        assertEquals("recipient-fingerprint", record.recipientIdentity)
        assertEquals(DeliveryState.QUEUED, record.state)
        assertEquals(sample().ciphertext.toList(), EncryptedMessageEnvelope.decodeBase64(record.payloadBase64).ciphertext.toList())
    }

    @Test fun rejectsUnknownEnvelopeMagic() {
        val bytes = Base64.getDecoder().decode(sample().encodeBase64())
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelope.decodeBase64(Base64.getEncoder().encodeToString(bytes))
        }
    }

    @Test fun rejectsTrailingBytes() {
        val bytes = Base64.getDecoder().decode(sample().encodeBase64()) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelope.decodeBase64(Base64.getEncoder().encodeToString(bytes))
        }
    }

    @Test fun rejectsWrongNonceLength() {
        assertThrows(IllegalArgumentException::class.java) {
            sample().copy(nonce = byteArrayOf(1, 2, 3))
        }
    }
}