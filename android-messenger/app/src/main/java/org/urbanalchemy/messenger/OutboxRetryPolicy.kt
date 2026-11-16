package org.urbanalchemy.messenger

/**
 * Bounded exponential retry policy for durable outbox delivery.
 *
 * The caller must persist lastAttemptAtEpochMillis alongside its worker state and
 * schedule the returned retry time with Android WorkManager (or an equivalent
 * lifecycle-aware scheduler). This policy itself is deterministic and side-effect free.
 */
object OutboxRetryPolicy {
    const val DEFAULT_MAX_RETRIES = 5
    const val BASE_DELAY_MILLIS = 2_000L
    const val MAX_DELAY_MILLIS = 15 * 60 * 1_000L

    sealed class Decision {
        data class RetryAt(val epochMillis: Long, val retryOrdinal: Int) : Decision()
        data class Stop(val reason: StopReason) : Decision()
    }

    enum class StopReason { ALREADY_DELIVERED, NOT_RETRYABLE, EXPIRED, RETRY_LIMIT_REACHED }

    /**
     * [attemptCount] is the number of retries already consumed by the outbox record.
     * [lastAttemptAtEpochMillis] is supplied by durable scheduler metadata.
     * [jitterPermille] spreads reconnect storms; 1000 means no jitter.
     */
    fun decide(
        record: OutboxRecord,
        nowEpochMillis: Long,
        lastAttemptAtEpochMillis: Long,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        jitterPermille: Int = 1_000
    ): Decision {
        require(nowEpochMillis >= 0 && lastAttemptAtEpochMillis >= 0)
        require(maxRetries >= 0)
        require(jitterPermille in 750..1_250)

        if (record.state == DeliveryState.DELIVERED) return Decision.Stop(StopReason.ALREADY_DELIVERED)
        if (record.state != DeliveryState.FAILED) return Decision.Stop(StopReason.NOT_RETRYABLE)
        if (record.expiresAtEpochMillis != null && nowEpochMillis >= record.expiresAtEpochMillis) {
            return Decision.Stop(StopReason.EXPIRED)
        }
        val retryOrdinal = record.attemptCount + 1
        if (retryOrdinal > maxRetries) return Decision.Stop(StopReason.RETRY_LIMIT_REACHED)

        val exponent = (retryOrdinal - 1).coerceAtMost(30)
        val base = (BASE_DELAY_MILLIS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MILLIS)
        val delay = (base * jitterPermille / 1_000L).coerceAtMost(MAX_DELAY_MILLIS)
        val target = safeAdd(lastAttemptAtEpochMillis, delay)
        if (record.expiresAtEpochMillis != null && target >= record.expiresAtEpochMillis) {
            return Decision.Stop(StopReason.EXPIRED)
        }
        return Decision.RetryAt(target, retryOrdinal)
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
