package org.urbanalchemy.messenger

import android.content.Context
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
        context = org.robolectric.RuntimeEnvironment.getApplication()
        databaseName = "outbox-test-${System.nanoTime()}.db"
        // Route the helper database to a unique test database.
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


    @Test fun retryTimePersistsAndMessageIsNotClaimedEarly() {
        store.enqueue(sample())
        store.transition("message-persist-1", DeliveryState.FAILED, "peer unavailable")
        val decision = store.scheduleRetry("message-persist-1", 1_000L)
        assertEquals(OutboxRetryPolicy.Decision.RetryAt(3_000L, 1), decision)
        assertEquals(emptyList<OutboxClaim>(), store.claimDue(2_999L))
        store.close()
        store = PersistentOutbox(object : android.content.ContextWrapper(context) {
            override fun getDatabasePath(name: String) = super.getDatabasePath(databaseName)
        })
        val claim = store.claimDue(3_000L).single()
        assertEquals(3_000L, claim.record.nextAttemptAtEpochMillis)
        assertEquals(1, claim.record.attemptCount)
    }

    @Test fun activeLeasePreventsSecondWorkerAndExpiredLeaseCanBeReclaimed() {
        store.enqueue(sample())
        val first = store.claimDue(1_000L, leaseDurationMillis = 100L).single()
        assertEquals(emptyList<OutboxClaim>(), store.claimDue(1_050L))
        val second = store.claimDue(1_100L, leaseDurationMillis = 100L).single()
        assertNotEquals(first.token, second.token)
        try {
            store.completeClaim(first, DeliveryState.ROUTER_ACCEPTED)
            org.junit.Assert.fail("A stale worker must not complete a reclaimed message.")
        } catch (_: IllegalArgumentException) { }
        assertEquals(DeliveryState.ROUTER_ACCEPTED, store.completeClaim(second, DeliveryState.ROUTER_ACCEPTED).state)
    }

    @Test fun expiredQueuedMessagesAreNotClaimed() {
        store.enqueue(sample().copy(expiresAtEpochMillis = 2_000L))
        assertEquals(emptyList<OutboxClaim>(), store.claimDue(2_000L))
    }

    @Test fun releasedClaimCanBeClaimedAgainImmediately() {
        store.enqueue(sample())
        val first = store.claimDue(1_000L).single()
        assertEquals(true, store.releaseClaim(first))
        assertEquals(1, store.claimDue(1_000L).size)
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
