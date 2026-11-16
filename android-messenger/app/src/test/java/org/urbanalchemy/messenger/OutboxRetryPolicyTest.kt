package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test

class OutboxRetryPolicyTest {
    private fun record(
        state: DeliveryState = DeliveryState.FAILED,
        attemptCount: Int = 0,
        expiry: Long? = null
    ) = OutboxRecord("m-1", "peer", "opaque-encrypted-payload", 1_000L, expiry, attemptCount, state)

    @Test fun firstRetryUsesBaseDelay() {
        assertEquals(OutboxRetryPolicy.Decision.RetryAt(12_000L, 1),
            OutboxRetryPolicy.decide(record(), 10_000L, 10_000L))
    }

    @Test fun delayDoublesAndIsCapped() {
        assertEquals(OutboxRetryPolicy.Decision.RetryAt(104_000L, 2),
            OutboxRetryPolicy.decide(record(attemptCount = 1), 100_000L, 100_000L))
        assertEquals(OutboxRetryPolicy.Decision.RetryAt(1_064_000L, 6),
            OutboxRetryPolicy.decide(record(attemptCount = 5), 1_000_000L, 1_000_000L, maxRetries = 6))
        assertEquals(OutboxRetryPolicy.Decision.RetryAt(1_900_000L, 15),
            OutboxRetryPolicy.decide(record(attemptCount = 14), 1_000_000L, 1_000_000L, maxRetries = 20))
    }

    @Test fun retryLimitStopsFurtherAttempts() {
        assertEquals(OutboxRetryPolicy.Decision.Stop(OutboxRetryPolicy.StopReason.RETRY_LIMIT_REACHED),
            OutboxRetryPolicy.decide(record(attemptCount = 5), 10_000L, 10_000L))
    }

    @Test fun expiredMessagesAreNeverRetried() {
        assertEquals(OutboxRetryPolicy.Decision.Stop(OutboxRetryPolicy.StopReason.EXPIRED),
            OutboxRetryPolicy.decide(record(expiry = 9_000L), 10_000L, 8_000L))
    }

    @Test fun retryThatWouldOccurAfterExpiryIsRejected() {
        assertEquals(OutboxRetryPolicy.Decision.Stop(OutboxRetryPolicy.StopReason.EXPIRED),
            OutboxRetryPolicy.decide(record(expiry = 11_000L), 10_000L, 10_000L))
    }

    @Test fun deliveredAndNonFailedMessagesAreNotRetried() {
        assertEquals(OutboxRetryPolicy.Decision.Stop(OutboxRetryPolicy.StopReason.ALREADY_DELIVERED),
            OutboxRetryPolicy.decide(record(state = DeliveryState.DELIVERED), 10_000L, 10_000L))
        assertEquals(OutboxRetryPolicy.Decision.Stop(OutboxRetryPolicy.StopReason.NOT_RETRYABLE),
            OutboxRetryPolicy.decide(record(state = DeliveryState.QUEUED), 10_000L, 10_000L))
    }

    @Test fun jitterBoundsAreAppliedDeterministically() {
        assertEquals(OutboxRetryPolicy.Decision.RetryAt(11_500L, 1),
            OutboxRetryPolicy.decide(record(), 10_000L, 10_000L, jitterPermille = 750))
        assertEquals(OutboxRetryPolicy.Decision.RetryAt(12_500L, 1),
            OutboxRetryPolicy.decide(record(), 10_000L, 10_000L, jitterPermille = 1_250))
    }

    @Test fun invalidClockAndJitterAreRejected() {
        try { OutboxRetryPolicy.decide(record(), -1L, 0L); fail("Expected invalid clock rejection") }
        catch (_: IllegalArgumentException) { }
        try { OutboxRetryPolicy.decide(record(), 10_000L, 10_000L, jitterPermille = 500); fail("Expected invalid jitter rejection") }
        catch (_: IllegalArgumentException) { }
    }
}
