package org.urbanalchemy.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Durable transport outbox, isolated from chat-history schema migrations. */
class PersistentOutbox(context: Context) : SQLiteOpenHelper(context, "ua_transport_outbox.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE outbox (
            message_id TEXT PRIMARY KEY,
            recipient_identity TEXT NOT NULL,
            payload_base64 TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER,
            attempt_count INTEGER NOT NULL DEFAULT 0,
            state TEXT NOT NULL,
            last_error TEXT
        )""")
        db.execSQL("CREATE INDEX outbox_state_created ON outbox(state, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema changes must use additive migrations.
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

    fun transition(messageId: String, next: DeliveryState, error: String? = null): OutboxRecord = inTransaction { db ->
        val current = find(db, messageId) ?: error("Outbox message not found.")
        val updated = MessageDelivery.transition(current, next, error)
        val values = ContentValues().apply {
            put("state", updated.state.name)
            put("attempt_count", updated.attemptCount)
            if (updated.lastError == null) putNull("last_error") else put("last_error", updated.lastError)
        }
        db.update("outbox", values, "message_id = ?", arrayOf(messageId))
        updated
    }

    fun acknowledge(ack: DeliveryAcknowledgement): OutboxRecord = inTransaction { db ->
        val current = find(db, ack.messageId) ?: error("Outbox message not found.")
        val updated = MessageDelivery.acknowledge(current, ack)
        val values = ContentValues().apply {
            put("state", updated.state.name)
            if (updated.lastError == null) putNull("last_error") else put("last_error", updated.lastError)
        }
        db.update("outbox", values, "message_id = ?", arrayOf(updated.messageId))
        updated
    }

    private fun inTransaction(block: (SQLiteDatabase) -> OutboxRecord): OutboxRecord {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
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
        lastError = if (isNull(7)) null else getString(7)
    )

    companion object {
        private val COLUMNS = arrayOf("message_id", "recipient_identity", "payload_base64", "created_at", "expires_at", "attempt_count", "state", "last_error")
    }
}
