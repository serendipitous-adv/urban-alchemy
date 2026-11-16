package org.urbanalchemy.messenger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class EncryptedMessageEnvelopeTest {
    private fun sample() = EncryptedMessageEnvelope(
        "message-123", "sender-fingerprint", "recipient-fingerprint", 1_000L, 60_000L,
        ByteArray(12) { it.toByte() }, ByteArray(32) { (it + 1).toByte() }, ByteArray(70) { (it + 2).toByte() }
    )

    @Test fun binaryEnvelopeRoundTripsAllAuthenticatedFields() {
        val original = sample()
        val decoded = EncryptedMessageEnvelope.decodeBase64(original.encodeBase64())
        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.senderIdentity, decoded.senderIdentity)
        assertEquals(original.recipientIdentity, decoded.recipientIdentity)
        assertEquals(original.createdAtEpochMillis, decoded.createdAtEpochMillis)
        assertEquals(original.expiresAtEpochMillis, decoded.expiresAtEpochMillis)
        assertArrayEquals(original.nonce, decoded.nonce)
        assertArrayEquals(original.ciphertext, decoded.ciphertext)
        assertArrayEquals(original.signature, decoded.signature)
        assertEquals(2, decoded.version)
    }

    @Test fun cryptoMessageConvertsWithoutDroppingSignature() {
        val crypto = SessionMessageCrypto.Message("m-9", "alice", "bob", 2_000L, ByteArray(12), ByteArray(32) { 1 }, ByteArray(70) { 2 })
        val envelope = EncryptedMessageEnvelope.fromCryptoMessage(crypto)
        assertArrayEquals(crypto.signature, EncryptedMessageEnvelope.decodeBase64(envelope.encodeBase64()).signature)
    }

    @Test fun envelopeCreatesQueuedOutboxRecord() {
        val record = sample().toOutboxRecord()
        val decoded = EncryptedMessageEnvelope.decodeBase64(record.payloadBase64)
        assertEquals("message-123", record.messageId)
        assertEquals("recipient-fingerprint", record.recipientIdentity)
        assertEquals(DeliveryState.QUEUED, record.state)
        assertArrayEquals(sample().signature, decoded.signature)
    }

    @Test fun rejectsUnknownMagicAndTrailingBytes() {
        val bytes = Base64.getDecoder().decode(sample().encodeBase64())
        val badMagic = bytes.clone().also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) { EncryptedMessageEnvelope.decodeBase64(Base64.getEncoder().encodeToString(badMagic)) }
        assertThrows(IllegalArgumentException::class.java) { EncryptedMessageEnvelope.decodeBase64(Base64.getEncoder().encodeToString(bytes + byteArrayOf(1))) }
    }

    @Test fun rejectsInvalidNonceCiphertextAndMissingSignature() {
        assertThrows(IllegalArgumentException::class.java) { sample().copy(nonce = byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(ciphertext = byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(signature = byteArrayOf()) }
    }
}