package org.urbanalchemy.messenger

/**
 * Transport-independent delivery state. These states describe protocol evidence,
 * not merely whether a local database write succeeded.
 */
enum class DeliveryState {
    QUEUED,
    ROUTER_ACCEPTED,
    SENT,
    DELIVERED,
    FAILED
}

data class OutboxRecord(
    val messageId: String,
    val recipientIdentity: String,
    val payloadBase64: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val attemptCount: Int = 0,
    val state: DeliveryState = DeliveryState.QUEUED,
    val lastError: String? = null
) {
    init {
        require(messageId.isNotBlank()) { "Message ID must not be blank." }
        require(recipientIdentity.isNotBlank()) { "Recipient identity must not be blank." }
        require(payloadBase64.isNotBlank()) { "Encrypted payload must not be blank." }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative." }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > createdAtEpochMillis) {
            "Expiry must be later than creation."
        }
        require(attemptCount >= 0) { "Attempt count must not be negative." }
    }
}

/**
 * A delivery acknowledgement is only meaningful when it identifies both the
 * original message and the intended recipient. Signature verification is a
 * separate cryptographic step and must happen before this value is trusted.
 */
data class DeliveryAcknowledgement(
    val messageId: String,
    val recipientIdentity: String,
    val acknowledgedAtEpochMillis: Long
)

object MessageDelivery {
    fun transition(
        record: OutboxRecord,
        next: DeliveryState,
        error: String? = null
    ): OutboxRecord {
        if (record.state == next) return record // idempotent duplicate event

        val allowed = when (record.state) {
            DeliveryState.QUEUED ->
                next == DeliveryState.ROUTER_ACCEPTED || next == DeliveryState.FAILED
            DeliveryState.ROUTER_ACCEPTED ->
                next == DeliveryState.SENT || next == DeliveryState.FAILED
            DeliveryState.SENT ->
                next == DeliveryState.DELIVERED || next == DeliveryState.FAILED
            DeliveryState.FAILED ->
                next == DeliveryState.QUEUED
            DeliveryState.DELIVERED -> false
        }
        require(allowed) { "Invalid delivery transition: ${record.state} -> $next" }

        return record.copy(
            state = next,
            attemptCount = if (record.state == DeliveryState.FAILED && next == DeliveryState.QUEUED)
                record.attemptCount + 1 else record.attemptCount,
            lastError = if (next == DeliveryState.FAILED) error?.take(512) else null
        )
    }

    fun acknowledge(
        record: OutboxRecord,
        acknowledgement: DeliveryAcknowledgement
    ): OutboxRecord {
        require(acknowledgement.messageId == record.messageId) {
            "Acknowledgement message ID does not match."
        }
        require(acknowledgement.recipientIdentity == record.recipientIdentity) {
            "Acknowledgement recipient does not match."
        }
        require(acknowledgement.acknowledgedAtEpochMillis >= record.createdAtEpochMillis) {
            "Acknowledgement predates the message."
        }
        require(record.state == DeliveryState.SENT || record.state == DeliveryState.DELIVERED) {
            "A message must be sent before it can be acknowledged."
        }
        return transition(record, DeliveryState.DELIVERED)
    }
}
