package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SessionMessageCryptoTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun twoPeersExchangeAuthenticatedEncryptedMessages() {
        val alice = keyPair(); val bob = keyPair(); val root = ByteArray(32) { (it + 7).toByte() }
        val outbound = SessionMessageCrypto.encrypt(root, "hello Bob", "m-1", 1_800_000_000_000L, "alice", "bob", alice.private)
        assertEquals("hello Bob", SessionMessageCrypto.decrypt(root, outbound, "bob", "alice", alice.public))
        val reply = SessionMessageCrypto.encrypt(root, "hello Alice", "m-2", 1_800_000_000_001L, "bob", "alice", bob.private)
        assertEquals("hello Alice", SessionMessageCrypto.decrypt(root, reply, "alice", "bob", bob.public))
    }

    @Test fun rejectsTamperedCiphertext() {
        val alice = keyPair(); val root = ByteArray(32) { 3 }
        val message = SessionMessageCrypto.encrypt(root, "secret", "m-2", 10L, "alice", "bob", alice.private)
        val tampered = message.copy(ciphertext = message.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() })
        try { SessionMessageCrypto.decrypt(root, tampered, "bob", "alice", alice.public); fail("Expected tamper rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsWrongRecipientAndWrongSigningIdentity() {
        val alice = keyPair(); val mallory = keyPair(); val root = ByteArray(32) { 9 }
        val message = SessionMessageCrypto.encrypt(root, "private", "m-3", 12L, "alice", "bob", alice.private)
        try { SessionMessageCrypto.decrypt(root, message, "mallory", "alice", alice.public); fail("Expected recipient rejection") }
        catch (_: IllegalArgumentException) { }
        try { SessionMessageCrypto.decrypt(root, message, "bob", "alice", mallory.public); fail("Expected signature rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsWrongSessionKey() {
        val alice = keyPair(); val root = ByteArray(32) { 1 }
        val message = SessionMessageCrypto.encrypt(root, "private", "m-4", 13L, "alice", "bob", alice.private)
        try { SessionMessageCrypto.decrypt(ByteArray(32) { 2 }, message, "bob", "alice", alice.public); fail("Expected key rejection") }
        catch (_: javax.crypto.AEADBadTagException) { }
    }
}
