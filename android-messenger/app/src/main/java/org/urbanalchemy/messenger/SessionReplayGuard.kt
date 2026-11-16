package org.urbanalchemy.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Durable duplicate-offer guard. A successful insert means this offer ID was not previously accepted. */
class SessionReplayGuard(context: Context, databaseName: String = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE accepted_session_offers (
            sender_fingerprint TEXT NOT NULL,
            session_id TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            retain_until INTEGER NOT NULL,
            PRIMARY KEY(sender_fingerprint, session_id)
        )""")
        db.execSQL("CREATE INDEX accepted_session_offers_retention ON accepted_session_offers(retain_until)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future changes must use additive migrations.
    }

    /** Returns false for a previously accepted (sender, session) pair. */
    fun recordAccepted(offer: SignedSessionHandshake.VerifiedOffer, nowEpochMillis: Long): Boolean {
        require(nowEpochMillis >= 0) { "Current time must not be negative." }
        require(offer.senderFingerprint.isNotBlank() && offer.sessionId.isNotBlank()) { "Offer identity is incomplete." }
        require(offer.expiresAtEpochMillis >= nowEpochMillis) { "Cannot record an expired offer." }
        val retainUntil = if (offer.expiresAtEpochMillis > Long.MAX_VALUE - REPLAY_RETENTION_MILLIS) {
            Long.MAX_VALUE
        } else {
            offer.expiresAtEpochMillis + REPLAY_RETENTION_MILLIS
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("accepted_session_offers", "retain_until < ?", arrayOf(nowEpochMillis.toString()))
            val values = ContentValues().apply {
                put("sender_fingerprint", offer.senderFingerprint)
                put("session_id", offer.sessionId)
                put("expires_at", offer.expiresAtEpochMillis)
                put("retain_until", retainUntil)
            }
            val inserted = db.insertWithOnConflict(
                "accepted_session_offers", null, values, SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "ua_session_replay_guard.db"
        private const val REPLAY_RETENTION_MILLIS = 24L * 60L * 60L * 1000L
    }
}