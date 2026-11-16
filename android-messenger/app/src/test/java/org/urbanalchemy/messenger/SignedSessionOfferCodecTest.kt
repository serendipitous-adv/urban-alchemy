package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SignedSessionOfferCodecTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun sample(): Pair<SignedSessionHandshake.Offer, java.security.KeyPair> {
        val alice = keyPair()
        val bob = keyPair()
        val ephemeral = keyPair()
        val now = 1_800_000_000_000L
        val offer = SignedSessionHandshake.createOffer(
            "codec-session-1",
            SignedSessionHandshake.fingerprint(alice.public),
            SignedSessionHandshake.fingerprint(bob.public),
            ephemeral, alice, ByteArray(32) { it.toByte() }, now, now + 60_000L
        )
        return offer to alice
    }

    @Test fun signedOfferRoundTripsAndStillVerifiesAgainstPinnedKey() {
        val (offer, alice) = sample()
        val decoded = SignedSessionOfferCodec.decode(SignedSessionOfferCodec.encode(offer))
        assertEquals(offer.sessionId, decoded.sessionId)
        assertEquals(offer.senderFingerprint, decoded.senderFingerprint)
        assertEquals(offer.recipientFingerprint, decoded.recipientFingerprint)
        assertArrayEquals(offer.ephemeralPublicKey, decoded.ephemeralPublicKey)
        assertArrayEquals(offer.nonce, decoded.nonce)
        assertArrayEquals(offer.signature, decoded.signature)
        val verified = SignedSessionHandshake.verifyOffer(
            decoded, offer.senderFingerprint, offer.recipientFingerprint,
            alice.public, offer.issuedAtEpochMillis + 1_000L
        )
        assertEquals(offer.sessionId, verified.sessionId)
    }

    @Test fun rejectsUnknownMagicVersionAndTrailingBytes() {
        val encoded = SignedSessionOfferCodec.encode(sample().first)
        assertThrows(IllegalArgumentException::class.java) {
            SignedSessionOfferCodec.decode(encoded.clone().also { it[0] = 0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignedSessionOfferCodec.decode(encoded.clone().also { it[7] = 2 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignedSessionOfferCodec.decode(encoded + byteArrayOf(0))
        }
    }

    @Test fun rejectsTruncatedAndOversizedInputs() {
        val encoded = SignedSessionOfferCodec.encode(sample().first)
        assertThrows(Exception::class.java) {
            SignedSessionOfferCodec.decode(encoded.copyOf(encoded.size - 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignedSessionOfferCodec.decode(ByteArray(8_193))
        }
    }

    @Test fun rejectsInvalidSignatureAndNonceLengthsOnEncode() {
        val offer = sample().first
        assertThrows(IllegalArgumentException::class.java) {
            SignedSessionOfferCodec.encode(offer.copy(signature = byteArrayOf(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignedSessionOfferCodec.encode(offer.copy(nonce = byteArrayOf(1, 2)))
        }
    }
}
