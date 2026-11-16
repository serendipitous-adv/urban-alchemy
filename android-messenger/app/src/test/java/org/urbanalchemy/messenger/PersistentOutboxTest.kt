package org.urbanalchemy.messenger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistentOutboxTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var store: PersistentOutbox

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "outbox-test-${System.nanoTime()}.db"
        store = PersistentOutbox(context).apply {
            close()
        }
        // Use a unique database name by constructing the helper through a test context wrapper.
        val testContext = object : android.content.ContextWrapper(context) {
            override fun getDatabasePath(name: String) = super.getDatabasePath(databaseName)
        }
        store = PersistentOutbox(testContext)
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    private fun sample() = OutboxRecord(
        messageId = "message-persist-1",
        recipientIdentity = "peer-fingerprint",
        payloadBase64 = "ciphertext-envelope-base64",
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 60_000L
    )

    @Test fun queuedPayloadSurvivesClosingAndReopeningDatabase() {
        store.enqueue(sample())
        store.close()
        store = PersistentOutbox(object : android.content.ContextWrapper(context) {
            override fun getDatabasePath(name: String) = super.getDatabasePath(databaseName)
        })
        assertEquals(listOf(sample()), store.list())
    }

    @Test fun stateAndRetryMetadataSurviveReopeningDatabase() {
        store.enqueue(sample())
        store.transition("message-persist-1", DeliveryState.FAILED, "no peer")
        store.transition("message-persist-1", DeliveryState.QUEUED)
        store.close()
        store = PersistentOutbox(object : android.content.ContextWrapper(context) {
            override fun getDatabasePath(name: String) = super.getDatabasePath(databaseName)
        })
        val restored = store.list().single()
        assertEquals(DeliveryState.QUEUED, restored.state)
        assertEquals(1, restored.attemptCount)
        assertNull(restored.lastError)
    }

    @Test fun acknowledgementMovesSentRecordToDelivered() {
        store.enqueue(sample())
        store.transition("message-persist-1", DeliveryState.ROUTER_ACCEPTED)
        store.transition("message-persist-1", DeliveryState.SENT)
        val delivered = store.acknowledge(
            DeliveryAcknowledgement("message-persist-1", "peer-fingerprint", 2_000L)
        )
        assertEquals(DeliveryState.DELIVERED, delivered.state)
    }
}
