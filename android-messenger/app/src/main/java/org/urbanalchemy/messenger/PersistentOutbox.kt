package org.urbanalchemy.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Durable outbox with additive retry metadata and crash-recoverable exclusive claims. */
class PersistentOutbox(context: Context) : SQLiteOpenHelper(context, "ua_transport_outbox.db", null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE outbox (
            message_id TEXT PRIMARY KEY,
            recipient_identity TEXT NOT NULL,
            payload_base64 TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER,
            attempt_count INTEGER NOT NULL DEFAULT 0,
            state TEXT NOT NULL,
            last_error TEXT,
            next_attempt_at INTEGER NOT NULL DEFAULT 0,
            lease_token TEXT,
            lease_until INTEGER
        )""")
        db.execSQL("CREATE INDEX outbox_due ON outbox(state, next_attempt_at, lease_until)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE outbox ADD COLUMN lease_token TEXT")
            db.execSQL("ALTER TABLE outbox ADD COLUMN lease_until INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS outbox_due ON outbox(state, next_attempt_at, lease_until)")
        }
    }

    fun enqueue(record: OutboxRecord) {
        require(record.state == DeliveryState.QUEUED) { "New outbox records must start queued." }
        val values = ContentValues().apply {
            put("message_id", record.messageId)
            put("recipient_identity", record.recipientIdentity)
            put("payload_base64", record.payloadBase64)
            put("created_at", record.createdAtEpochMillis)
            if (record.expiresAtEpochMillis == null) putNull("expires_at") else put("expires_at", record.expiresAtEpochMillis)
            put("attempt_count", record.attemptCount)
            put("state", record.state.name)
            putNull("last_error")
            put("next_attempt_at", record.nextAttemptAtEpochMillis)
            putNull("lease_token")
            putNull("lease_until")
        }
        writableDatabase.insertOrThrow("outbox", null, values)
    }

    fun list(states: Set<DeliveryState>? = null): List<OutboxRecord> {
        val selection = if (states.isNullOrEmpty()) null else "state IN (" + states.joinToString(",") { "?" } + ")"
        val args = states?.takeIf { it.isNotEmpty() }?.map { it.name }?.toTypedArray()
        val records = mutableListOf<OutboxRecord>()
        readableDatabase.query("outbox", COLUMNS, selection, args, null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) records += cursor.toRecord()
        }
        return records
    }

    /** Atomically converts a failed record back to QUEUED at its persisted retry time. */
    fun scheduleRetry(
        messageId: String,
        nowEpochMillis: Long,
        jitterPermille: Int = 1_000,
        maxRetries: Int = OutboxRetryPolicy.DEFAULT_MAX_RETRIES
    ): OutboxRetryPolicy.Decision = inTransaction { db ->
        val current = find(db, messageId) ?: throw IllegalStateException("Outbox message not found.")
        val decision = OutboxRetryPolicy.decide(current, nowEpochMillis, nowEpochMillis, maxRetries, jitterPermille)
        if (decision is OutboxRetryPolicy.Decision.RetryAt) {
            val queued = MessageDelivery.transition(current, DeliveryState.QUEUED)
            val values = ContentValues().apply {
                put("state", queued.state.name)
                put("attempt_count", queued.attemptCount)
                putNull("last_error")
                put("next_attempt_at", decision.epochMillis)
            }
            db.update("outbox", values, "message_id = ? AND state = ?", arrayOf(messageId, DeliveryState.FAILED.name))
        }
        decision
    }

    /** Claims due messages in one transaction. Expired leases are safely reclaimable after process death. */
    fun claimDue(nowEpochMillis: Long, leaseDurationMillis: Long = DEFAULT_LEASE_MILLIS, limit: Int = 10): List<OutboxClaim> {
        require(nowEpochMillis >= 0 && leaseDurationMillis > 0 && limit in 1..100)
        val leaseUntil = safeAdd(nowEpochMillis, leaseDurationMillis)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = mutableListOf<String>()
            db.query(
                "outbox", arrayOf("message_id"),
                "state = ? AND next_attempt_at <= ? AND (expires_at IS NULL OR expires_at > ?) AND (lease_until IS NULL OR lease_until <= ?)",
                arrayOf(DeliveryState.QUEUED.name, nowEpochMillis.toString(), nowEpochMillis.toString(), nowEpochMillis.toString()),
                null, null, "created_at ASC", limit.toString()
            ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
            val claims = mutableListOf<OutboxClaim>()
            ids.forEach { id ->
                val token = UUID.randomUUID().toString()
                val values = ContentValues().apply { put("lease_token", token); put("lease_until", leaseUntil) }
                val changed = db.update(
                    "outbox", values,
                    "message_id = ? AND state = ? AND next_attempt_at <= ? AND (expires_at IS NULL OR expires_at > ?) AND (lease_until IS NULL OR lease_until <= ?)",
                    arrayOf(id, DeliveryState.QUEUED.name, nowEpochMillis.toString(), nowEpochMillis.toString(), nowEpochMillis.toString())
                )
                if (changed == 1) {
                    val record = find(db, id)
                    if (record != null) claims += OutboxClaim(record, token, leaseUntil)
                }
            }
            db.setTransactionSuccessful()
            return claims
        } finally { db.endTransaction() }
    }

    /** Completes a claim only if its lease token is still current. */
    fun completeClaim(claim: OutboxClaim, next: DeliveryState, error: String? = null): OutboxRecord = inTransaction { db ->
        val current = find(db, claim.record.messageId) ?: error("Outbox message not found.")
        requireLease(db, claim)
        val updated = MessageDelivery.transition(current, next, error)
        val values = ContentValues().apply {
            put("state", updated.state.name)
            put("attempt_count", updated.attemptCount)
            if (updated.lastError == null) putNull("last_error") else put("last_error", updated.lastError)
            put("next_attempt_at", updated.nextAttemptAtEpochMillis)
            putNull("lease_token"); putNull("lease_until")
        }
        db.update("outbox", values, "message_id = ?", arrayOf(updated.messageId))
        updated
    }

    /** Releases a claim after a transient local interruption; the message remains due. */
    fun releaseClaim(claim: OutboxClaim): Boolean {
        val values = ContentValues().apply { putNull("lease_token"); putNull("lease_until") }
        return writableDatabase.update(
            "outbox", values, "message_id = ? AND lease_token = ?",
            arrayOf(claim.record.messageId, claim.token)
        ) == 1
    }

    fun transition(messageId: String, next: DeliveryState, error: String? = null): OutboxRecord = inTransaction { db ->
        val current = find(db, messageId) ?: error("Outbox message not found.")
        val updated = MessageDelivery.transition(current, next, error)
        val values = ContentValues().apply {
            put("state", updated.state.name)
            put("attempt_count", updated.attemptCount)
            if (updated.lastError == null) putNull("last_error") else put("last_error", updated.lastError)
            put("next_attempt_at", updated.nextAttemptAtEpochMillis)
            putNull("lease_token"); putNull("lease_until")
        }
        db.update("outbox", values, "message_id = ?", arrayOf(messageId))
        updated
    }

    fun acknowledge(ack: DeliveryAcknowledgement): OutboxRecord = inTransaction { db ->
        val current = find(db, ack.messageId) ?: error("Outbox message not found.")
        val updated = MessageDelivery.acknowledge(current, ack)
        val values = ContentValues().apply {
            put("state", updated.state.name)
            putNull("last_error"); put("next_attempt_at", 0L)
            putNull("lease_token"); putNull("lease_until")
        }
        db.update("outbox", values, "message_id = ?", arrayOf(updated.messageId))
        updated
    }

    private fun requireLease(db: SQLiteDatabase, claim: OutboxClaim) {
        db.query("outbox", arrayOf("lease_token"), "message_id = ?", arrayOf(claim.record.messageId), null, null, null).use { c ->
            require(c.moveToFirst() && !c.isNull(0) && c.getString(0) == claim.token) {
                "Outbox claim is no longer owned by this worker."
            }
        }
    }

    private fun <T> inTransaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }

    private fun find(db: SQLiteDatabase, id: String): OutboxRecord? {
        db.query("outbox", COLUMNS, "message_id = ?", arrayOf(id), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toRecord() else null
        }
    }

    private fun android.database.Cursor.toRecord() = OutboxRecord(
        messageId = getString(0), recipientIdentity = getString(1), payloadBase64 = getString(2),
        createdAtEpochMillis = getLong(3), expiresAtEpochMillis = if (isNull(4)) null else getLong(4),
        attemptCount = getInt(5), state = DeliveryState.valueOf(getString(6)),
        lastError = if (isNull(7)) null else getString(7),
        nextAttemptAtEpochMillis = getLong(8)
    )

    private fun safeAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    companion object {
        private const val DATABASE_VERSION = 2
        private const val DEFAULT_LEASE_MILLIS = 60_000L
        private val COLUMNS = arrayOf(
            "message_id", "recipient_identity", "payload_base64", "created_at", "expires_at",
            "attempt_count", "state", "last_error", "next_attempt_at"
        )
    }
}
