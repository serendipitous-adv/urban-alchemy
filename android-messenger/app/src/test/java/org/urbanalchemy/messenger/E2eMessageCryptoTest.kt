package org.urbanalchemy.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class E2eMessageCryptoTest {
    private fun signingPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private data class Peer(val signing: java.security.KeyPair, val agreement: java.security.KeyPair, val verified: E2eMessageCrypto.VerifiedPeerIdentity)

    private fun peer(): Peer {
        val signing = signingPair()
        val agreement = E2eMessageCrypto.generateAgreementKeyPair()
        val bundle = E2eMessageCrypto.createIdentityBundle(signing, agreement.public)
        val verified = E2eMessageCrypto.verifyIdentityBundle(bundle, bundle.fingerprint)
        return Peer(signing, agreement, verified)
    }

    @Test fun twoPeersCanEncryptAndDecryptWithoutSharingPlaintextKey() {
        val alice = peer()
        val bob = peer()
        val message = E2eMessageCrypto.encrypt(
            "hello over the offline link", "msg-1", 10_000L, alice.verified.fingerprint,
            alice.signing.private, alice.agreement.private, bob.verified
        )
        assertEquals(
            "hello over the offline link",
            E2eMessageCrypto.decrypt(message, bob.verified.fingerprint, bob.agreement.private, alice.verified)
        )
    }

    @Test fun rejectsIdentityBundleWithWrongExpectedFingerprint() {
        val signing = signingPair()
        val agreement = E2eMessageCrypto.generateAgreementKeyPair()
        val bundle = E2eMessageCrypto.createIdentityBundle(signing, agreement.public)
        assertThrows(IllegalArgumentException::class.java) {
            E2eMessageCrypto.verifyIdentityBundle(bundle, "00:00:00")
        }
    }

    @Test fun rejectsTamperedIdentityBundleSignature() {
        val signing = signingPair()
        val agreement = E2eMessageCrypto.generateAgreementKeyPair()
        val bundle = E2eMessageCrypto.createIdentityBundle(signing, agreement.public)
        val tampered = bundle.copy(agreementPublicKey = E2eMessageCrypto.generateAgreementKeyPair().public.encoded)
        assertThrows(IllegalArgumentException::class.java) {
            E2eMessageCrypto.verifyIdentityBundle(tampered, bundle.fingerprint)
        }
    }

    @Test fun rejectsTamperedCiphertextBeforeReturningPlaintext() {
        val alice = peer()
        val bob = peer()
        val message = E2eMessageCrypto.encrypt(
            "do not leak", "msg-2", 20_000L, alice.verified.fingerprint,
            alice.signing.private, alice.agreement.private, bob.verified
        )
        val changed = message.copy(ciphertext = message.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertThrows(IllegalArgumentException::class.java) {
            E2eMessageCrypto.decrypt(changed, bob.verified.fingerprint, bob.agreement.private, alice.verified)
        }
    }

    @Test fun rejectsMessageForDifferentRecipient() {
        val alice = peer()
        val bob = peer()
        val mallory = peer()
        val message = E2eMessageCrypto.encrypt(
            "private", "msg-3", 30_000L, alice.verified.fingerprint,
            alice.signing.private, alice.agreement.private, bob.verified
        )
        assertThrows(IllegalArgumentException::class.java) {
            E2eMessageCrypto.decrypt(message, mallory.verified.fingerprint, mallory.agreement.private, alice.verified)
        }
    }

    @Test fun rejectsMessageSignedByAnotherIdentity() {
        val alice = peer()
        val bob = peer()
        val mallory = peer()
        val message = E2eMessageCrypto.encrypt(
            "private", "msg-4", 40_000L, alice.verified.fingerprint,
            alice.signing.private, alice.agreement.private, bob.verified
        )
        assertThrows(IllegalArgumentException::class.java) {
            E2eMessageCrypto.decrypt(message, bob.verified.fingerprint, bob.agreement.private, mallory.verified)
        }
    }
}