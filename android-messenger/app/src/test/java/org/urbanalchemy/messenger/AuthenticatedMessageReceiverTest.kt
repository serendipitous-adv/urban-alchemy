package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom

class AuthenticatedMessageReceiverTest {
    private class MemoryStore : IncomingMessageStore {
        val messages = linkedMapOf<String, ReceivedMessage>()
        override fun insertIfAbsent(message: ReceivedMessage): Boolean {
            if (messages.containsKey(message.messageId)) return false
            messages[message.messageId] = message
            return true
        }
        override fun contains(messageId: String) = messages.containsKey(messageId)
    }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private data class Fixture(
        val receiver: AuthenticatedMessageReceiver,
        val store: MemoryStore,
        val sessionKey: ByteArray,
        val sender: java.security.KeyPair,
        val senderId: String,
        val recipientId: String,
        val encoded: String
    )

    private fun fixture(expiry: Long? = 20_000L): Fixture {
        val sender = keyPair()
        val recipient = keyPair()
        val senderId = SignedSessionHandshake.fingerprint(sender.public)
        val recipientId = SignedSessionHandshake.fingerprint(recipient.public)
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val crypto = SessionMessageCrypto.encrypt(
            key, "hello offline", "message-001", 10_000L,
            senderId, recipientId, sender.private
        )
        val envelope = EncryptedMessageEnvelope.fromCryptoMessage(crypto, expiry)
        val store = MemoryStore()
        return Fixture(AuthenticatedMessageReceiver(store), store, key, sender, senderId, recipientId, envelope.encodeBase64())
    }

    @Test fun authenticatedMessageIsStoredAndDecrypted() {
        val f = fixture()
        assertEquals(
            AuthenticatedMessageReceiver.Result.STORED,
            f.receiver.receive(f.encoded, f.sessionKey, f.senderId, f.recipientId, f.sender.public, 11_000L)
        )
        assertEquals("hello offline", f.store.messages["message-001"]?.text)
    }

    @Test fun duplicateMessageIsNotInsertedTwice() {
        val f = fixture()
        f.receiver.receive(f.encoded, f.sessionKey, f.senderId, f.recipientId, f.sender.public, 11_000L)
        assertEquals(
            AuthenticatedMessageReceiver.Result.DUPLICATE,
            f.receiver.receive(f.encoded, f.sessionKey, f.senderId, f.recipientId, f.sender.public, 11_000L)
        )
        assertEquals(1, f.store.messages.size)
    }

    @Test fun expiredMessageIsRejected() {
        val f = fixture(expiry = 10_500L)
        try {
            f.receiver.receive(f.encoded, f.sessionKey, f.senderId, f.recipientId, f.sender.public, 11_000L)
            fail("Expected expired message rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(f.store.messages.isEmpty())
    }

    @Test fun wrongSigningKeyIsRejectedBeforeStorage() {
        val f = fixture()
        try {
            f.receiver.receive(f.encoded, f.sessionKey, f.senderId, f.recipientId, keyPair().public, 11_000L)
            fail("Expected pinned-key rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(f.store.messages.isEmpty())
    }

    @Test fun tamperedSignatureDoesNotPoisonDuplicateStore() {
        val f = fixture()
        val envelope = EncryptedMessageEnvelope.decodeBase64(f.encoded)
        val changed = envelope.copy(signature = envelope.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        try {
            f.receiver.receive(changed.encodeBase64(), f.sessionKey, f.senderId, f.recipientId, f.sender.public, 11_000L)
            fail("Expected invalid signature rejection")
        } catch (_: IllegalArgumentException) { }
        assertFalse(f.store.contains("message-001"))
    }

    @Test fun futureDatedMessageIsRejected() {
        val f = fixture()
        try {
            f.receiver.receive(f.encoded, f.sessionKey, f.senderId, f.recipientId, f.sender.public, 9_000L)
            fail("Expected future timestamp rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(f.store.messages.isEmpty())
    }
}
