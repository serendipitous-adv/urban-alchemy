package org.urbanalchemy.messenger

enum class DeliveryState { QUEUED, ROUTER_ACCEPTED, SENT, DELIVERED, FAILED }

data class OutboxRecord(
    val messageId: String,
    val recipientIdentity: String,
    val payloadBase64: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val attemptCount: Int = 0,
    val state: DeliveryState = DeliveryState.QUEUED,
    val lastError: String? = null,
    /** Earliest epoch time at which a queued retry may be claimed. */
    val nextAttemptAtEpochMillis: Long = 0L
) {
    init {
        require(messageId.isNotBlank())
        require(recipientIdentity.isNotBlank())
        require(payloadBase64.isNotBlank())
        require(createdAtEpochMillis >= 0)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > createdAtEpochMillis)
        require(attemptCount >= 0)
        require(nextAttemptAtEpochMillis >= 0)
    }
}

data class DeliveryAcknowledgement(
    val messageId: String,
    val recipientIdentity: String,
    val acknowledgedAtEpochMillis: Long
)

/** A time-limited exclusive claim. The token must accompany every completion. */
data class OutboxClaim(
    val record: OutboxRecord,
    val token: String,
    val leaseUntilEpochMillis: Long
)

object MessageDelivery {
    fun transition(record: OutboxRecord, next: DeliveryState, error: String? = null): OutboxRecord {
        if (record.state == next) return record
        val allowed = when (record.state) {
            DeliveryState.QUEUED -> next == DeliveryState.ROUTER_ACCEPTED || next == DeliveryState.FAILED
            DeliveryState.ROUTER_ACCEPTED -> next == DeliveryState.SENT || next == DeliveryState.FAILED
            DeliveryState.SENT -> next == DeliveryState.DELIVERED || next == DeliveryState.FAILED
            DeliveryState.FAILED -> next == DeliveryState.QUEUED
            DeliveryState.DELIVERED -> false
        }
        require(allowed) { "Invalid delivery transition: ${record.state} -> $next" }
        return record.copy(
            state = next,
            attemptCount = if (record.state == DeliveryState.FAILED && next == DeliveryState.QUEUED)
                record.attemptCount + 1 else record.attemptCount,
            lastError = if (next == DeliveryState.FAILED) error?.take(512) else null,
            nextAttemptAtEpochMillis = if (next == DeliveryState.QUEUED) record.nextAttemptAtEpochMillis else 0L
        )
    }

    fun acknowledge(record: OutboxRecord, acknowledgement: DeliveryAcknowledgement): OutboxRecord {
        require(acknowledgement.messageId == record.messageId) { "Acknowledgement message ID does not match." }
        require(acknowledgement.recipientIdentity == record.recipientIdentity) { "Acknowledgement recipient does not match." }
        require(acknowledgement.acknowledgedAtEpochMillis >= record.createdAtEpochMillis) {
            "Acknowledgement predates the message."
        }
        require(record.state == DeliveryState.SENT || record.state == DeliveryState.DELIVERED) {
            "A message must be sent before it can be acknowledged."
        }
        return transition(record, DeliveryState.DELIVERED)
    }
}
