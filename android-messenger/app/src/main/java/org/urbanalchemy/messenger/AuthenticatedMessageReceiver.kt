package org.urbanalchemy.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.PublicKey

data class ReceivedMessage(
    val messageId: String,
    val senderFingerprint: String,
    val recipientFingerprint: String,
    val createdAtEpochMillis: Long,
    val text: String
)

interface IncomingMessageStore {
    /** Atomically inserts only the first copy of a message ID. */
    fun insertIfAbsent(message: ReceivedMessage): Boolean
    fun contains(messageId: String): Boolean
}

/** Verifies, decrypts, and deduplicates inbound authenticated envelopes. */
class AuthenticatedMessageReceiver(private val store: IncomingMessageStore) {
    enum class Result { STORED, DUPLICATE }

    fun receive(
        encodedEnvelope: String,
        sessionKey: ByteArray,
        expectedSenderFingerprint: String,
        expectedRecipientFingerprint: String,
        senderSigningPublicKey: PublicKey,
        nowEpochMillis: Long
    ): Result {
        require(nowEpochMillis >= 0) { "Current time must not be negative." }
        require(sessionKey.size == 32) { "Session key must be 256 bits." }
        require(SignedSessionHandshake.fingerprint(senderSigningPublicKey) == expectedSenderFingerprint) {
            "Signing key is not pinned to the expected sender fingerprint."
        }

        val envelope = EncryptedMessageEnvelope.decodeBase64(encodedEnvelope)
        require(envelope.senderIdentity == expectedSenderFingerprint) { "Unexpected message sender." }
        require(envelope.recipientIdentity == expectedRecipientFingerprint) { "Message is addressed to another identity." }
        require(envelope.createdAtEpochMillis <= nowEpochMillis) { "Message timestamp is in the future." }
        require(envelope.expiresAtEpochMillis == null || nowEpochMillis < envelope.expiresAtEpochMillis) {
            "Message has expired."
        }

        val message = SessionMessageCrypto.Message(
            envelope.messageId, envelope.senderIdentity, envelope.recipientIdentity,
            envelope.createdAtEpochMillis, envelope.nonce.copyOf(),
            envelope.ciphertext.copyOf(), envelope.signature.copyOf()
        )
        // Authenticate and decrypt before recording the ID: unauthenticated traffic
        // must never poison the persistent duplicate/replay table.
        val plaintext = SessionMessageCrypto.decrypt(
            sessionKey, message, expectedRecipientFingerprint,
            expectedSenderFingerprint, senderSigningPublicKey
        )
        val received = ReceivedMessage(
            envelope.messageId, envelope.senderIdentity, envelope.recipientIdentity,
            envelope.createdAtEpochMillis, plaintext
        )
        return if (store.insertIfAbsent(received)) Result.STORED else Result.DUPLICATE
    }
}

/** Durable inbox. Message bodies are encrypted at rest with the app's Android Keystore key. */
class PersistentIncomingMessageStore(context: Context) :
    SQLiteOpenHelper(context, "ua_messenger_inbox.db", null, 1), IncomingMessageStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE incoming_messages (
            message_id TEXT PRIMARY KEY,
            sender_fingerprint TEXT NOT NULL,
            recipient_fingerprint TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            nonce BLOB NOT NULL,
            ciphertext BLOB NOT NULL
        )""")
        db.execSQL("CREATE INDEX incoming_sender_time ON incoming_messages(sender_fingerprint, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema changes must use additive migrations.
    }

    override fun insertIfAbsent(message: ReceivedMessage): Boolean {
        val sealed = LocalMessageCrypto.encrypt(message.text)
        val values = ContentValues().apply {
            put("message_id", message.messageId)
            put("sender_fingerprint", message.senderFingerprint)
            put("recipient_fingerprint", message.recipientFingerprint)
            put("created_at", message.createdAtEpochMillis)
            put("nonce", sealed.nonce)
            put("ciphertext", sealed.ciphertext)
        }
        val row = writableDatabase.insertWithOnConflict(
            "incoming_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        return row != -1L
    }

    override fun contains(messageId: String): Boolean {
        readableDatabase.query(
            "incoming_messages", arrayOf("message_id"), "message_id = ?",
            arrayOf(messageId), null, null, null
        ).use { return it.moveToFirst() }
    }

    fun get(messageId: String): ReceivedMessage? {
        readableDatabase.query(
            "incoming_messages",
            arrayOf("message_id", "sender_fingerprint", "recipient_fingerprint", "created_at", "nonce", "ciphertext"),
            "message_id = ?", arrayOf(messageId), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val plaintext = LocalMessageCrypto.decrypt(cursor.getBlob(4), cursor.getBlob(5))
            return ReceivedMessage(
                cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getLong(3), plaintext
            )
        }
    }
}
