package org.urbanalchemy.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class Conversation(val id: Long, val title: String, val preview: String, val updatedAt: Long)
data class LocalMessage(
    val id: String,
    val conversationId: Long,
    val text: String,
    val createdAt: Long,
    val status: String
)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "ua_messenger.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                conversation_id INTEGER NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                nonce BLOB,
                ciphertext BLOB,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX messages_conversation_time ON messages(conversation_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN nonce BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN ciphertext BLOB")
        }
    }

    fun createConversation(title: String): Long {
        val values = ContentValues().apply {
            put("title", title.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("conversations", null, values)
    }

    fun listConversations(): List<Conversation> {
        val result = mutableListOf<Conversation>()
        readableDatabase.rawQuery(
            """SELECT c.id, c.title, c.created_at,
                (SELECT MAX(m.created_at) FROM messages m WHERE m.conversation_id = c.id) AS latest
                FROM conversations c ORDER BY COALESCE(latest, c.created_at) DESC""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val preview = latestMessagePreview(id)
                result += Conversation(id, cursor.getString(1), preview, cursor.getLong(2))
            }
        }
        return result
    }

    private fun latestMessagePreview(conversationId: Long): String {
        val message = listMessages(conversationId).lastOrNull() ?: return "No messages yet"
        return message.text
    }

    fun addQueuedMessage(conversationId: Long, text: String): String {
        val sealed = LocalMessageCrypto.encrypt(text)
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("conversation_id", conversationId)
            put("body", "")
            put("created_at", System.currentTimeMillis())
            put("status", "QUEUED")
            put("nonce", sealed.nonce)
            put("ciphertext", sealed.ciphertext)
        }
        writableDatabase.insertOrThrow("messages", null, values)
        return id
    }

    fun listMessages(conversationId: Long): List<LocalMessage> {
        val result = mutableListOf<LocalMessage>()
        readableDatabase.rawQuery(
            "SELECT id, conversation_id, body, created_at, status, nonce, ciphertext FROM messages WHERE conversation_id = ? ORDER BY created_at ASC",
            arrayOf(conversationId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val nonce = cursor.getBlob(5)
                val ciphertext = cursor.getBlob(6)
                val plaintext = if (nonce != null && ciphertext != null) {
                    LocalMessageCrypto.decrypt(nonce, ciphertext)
                } else {
                    cursor.getString(2) ?: ""
                }
                result += LocalMessage(
                    cursor.getString(0), cursor.getLong(1), plaintext,
                    cursor.getLong(3), cursor.getString(4)
                )
            }
        }
        return result
    }
}
