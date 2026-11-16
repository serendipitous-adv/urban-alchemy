package org.urbanalchemy.messenger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalStore(context: Context) : SQLiteOpenHelper(context, "ua_messenger.db", null, 1) {
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
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Use explicit, versioned migrations when the schema changes.
    }

    fun createConversation(title: String): Long {
        val values = android.content.ContentValues().apply {
            put("title", title)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("conversations", null, values)
    }

    fun listConversations(): List<Conversation> {
        val result = mutableListOf<Conversation>()
        readableDatabase.rawQuery(
            "SELECT id, title FROM conversations ORDER BY created_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Conversation(cursor.getLong(0), cursor.getString(1), "")
            }
        }
        return result
    }
}
