package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class SignedDeliveryAcknowledgementCodecTest {
    private fun keys() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun sample(): SignedDeliveryAcknowledgement {
        val sender = keys()
        val recipient = keys()
        return SignedDeliveryAcknowledgement.sign(
            "message-42",
            SignedSessionHandshake.fingerprint(sender.public),
            SignedSessionHandshake.fingerprint(recipient.public),
            1_700_000_000_000L,
            recipient.private
        )
    }

    @Test fun binaryAndBase64RoundTripPreserveVerifiableReceipt() {
        val original = sample()
        val decoded = SignedDeliveryAcknowledgementCodec.decode(SignedDeliveryAcknowledgementCodec.encode(original))
        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.originalSenderFingerprint, decoded.originalSenderFingerprint)
        assertEquals(original.originalRecipientFingerprint, decoded.originalRecipientFingerprint)
        assertEquals(original.acknowledgedAtEpochMillis, decoded.acknowledgedAtEpochMillis)
        assertEquals(original.signerFingerprint, decoded.signerFingerprint)
        assertArrayEquals(original.signature, decoded.signature)
        val base64 = SignedDeliveryAcknowledgementCodec.encodeBase64(original)
        val fromBase64 = SignedDeliveryAcknowledgementCodec.decodeBase64(base64)
        assertArrayEquals(original.signature, fromBase64.signature)
    }

    @Test fun decodedReceiptStillRequiresCryptographicVerification() {
        val sender = keys()
        val recipient = keys()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        val original = SignedDeliveryAcknowledgement.sign("m-verify", senderId, recipientId, 1_500L, recipient.private)
        val decoded = SignedDeliveryAcknowledgementCodec.decode(SignedDeliveryAcknowledgementCodec.encode(original))
        val verified = SignedDeliveryAcknowledgement.verify(
            decoded, "m-verify", senderId, recipientId, recipient.public, 2_000L, 1_000L
        )
        assertEquals("m-verify", verified.messageId)
    }

    @Test fun rejectsUnknownVersionAndTrailingBytes() {
        val encoded = SignedDeliveryAcknowledgementCodec.encode(sample())
        val unknown = encoded.copyOf().also { it[0] = 99.toByte() }
        assertInvalid { SignedDeliveryAcknowledgementCodec.decode(unknown) }
        val trailing = encoded + byteArrayOf(0)
        assertInvalid { SignedDeliveryAcknowledgementCodec.decode(trailing) }
    }

    @Test fun rejectsTruncatedAndOversizedInputs() {
        val encoded = SignedDeliveryAcknowledgementCodec.encode(sample())
        assertInvalid { SignedDeliveryAcknowledgementCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertInvalid { SignedDeliveryAcknowledgementCodec.decode(ByteArray(4_097)) }
    }

    @Test fun rejectsMalformedBase64AndInvalidLengths() {
        assertInvalid { SignedDeliveryAcknowledgementCodec.decodeBase64("%%%") }
        val encoded = SignedDeliveryAcknowledgementCodec.encode(sample())
        val invalidLength = encoded.copyOf()
        // Signature length follows version, four length-prefixed UTF-8 fields and timestamp.
        val signatureLengthOffset = 1 +
            4 + "message-42".toByteArray().size +
            4 + sampleFingerprintBytes +
            4 + sampleFingerprintBytes +
            8 +
            4 + sampleFingerprintBytes
        invalidLength[signatureLengthOffset] = 0x7f
        assertInvalid { SignedDeliveryAcknowledgementCodec.decode(invalidLength) }
    }

    private val sampleFingerprintBytes: Int
        get() = SignedSessionHandshake.fingerprint(keys().public).toByteArray().size

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected malformed acknowledgement rejection")
        } catch (_: IllegalArgumentException) {
        } catch (_: java.io.IOException) {
        }
    }
}
