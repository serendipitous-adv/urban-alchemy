package org.urbanalchemy.messenger

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthenticatedMessageOutboxTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var store: PersistentOutbox
    private lateinit var pipeline: AuthenticatedMessageOutbox
    private lateinit var alice: KeyPair
    private val sessionKey = ByteArray(32) { (it + 11).toByte() }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun testContext() = object : ContextWrapper(context) {
        override fun getDatabasePath(name: String) = super.getDatabasePath(databaseName)
    }

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        databaseName = "authenticated-outbox-${System.nanoTime()}.db"
        store = PersistentOutbox(testContext())
        pipeline = AuthenticatedMessageOutbox(store)
        alice = keyPair()
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    private fun signedMessage() = SessionMessageCrypto.encrypt(
        sessionKey = sessionKey,
        plaintext = "offline hello",
        messageId = "offline-message-1",
        createdAtEpochMillis = 10_000L,
        senderFingerprint = "alice-fingerprint",
        recipientFingerprint = "bob-fingerprint",
        senderSigningPrivateKey = alice.private
    )

    @Test fun signedMessageSurvivesOutboxCloseAndReopenAndDecrypts() {
        pipeline.enqueue(signedMessage(), expiresAtEpochMillis = 90_000L)
        store.close()
        store = PersistentOutbox(testContext())
        pipeline = AuthenticatedMessageOutbox(store)

        val restored = pipeline.list().single()
        assertEquals(DeliveryState.QUEUED, restored.state)
        assertEquals(
            "offline hello",
            pipeline.decrypt(
                restored, sessionKey, "bob-fingerprint", "alice-fingerprint",
                alice.public, nowEpochMillis = 20_000L
            )
        )
    }

    @Test fun duplicateMessageIdCannotBeEnqueuedTwice() {
        val message = signedMessage()
        pipeline.enqueue(message)
        assertThrows(IllegalStateException::class.java) { pipeline.enqueue(message) }
        assertEquals(1, pipeline.list().size)
    }

    @Test fun expiredMessageIsNotDecrypted() {
        val record = pipeline.enqueue(signedMessage(), expiresAtEpochMillis = 15_000L)
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.decrypt(
                record, sessionKey, "bob-fingerprint", "alice-fingerprint",
                alice.public, nowEpochMillis = 15_000L
            )
        }
    }

    @Test fun invalidSenderSignatureIsRejectedAfterPersistence() {
        val message = signedMessage()
        val altered = message.copy(signature = message.signature.clone().also { it[0] = (it[0].toInt() xor 1).toByte() })
        val record = pipeline.enqueue(altered)
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.decrypt(
                record, sessionKey, "bob-fingerprint", "alice-fingerprint",
                alice.public, nowEpochMillis = 11_000L
            )
        }
    }
}
