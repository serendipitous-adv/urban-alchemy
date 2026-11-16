package org.urbanalchemy.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class UaLocalIdentity(
    val playerId: String,
    val nodeId: String,
    val reticulumIdentityHash: String? = null,
    val reticulumPublicKey: String? = null,
    val lxmfDestination: String? = null
)

data class Contact(
    val id: String,
    val name: String,
    val destination: String,
    val createdAt: Long,
    val identityHash: String? = null,
    val publicKey: String? = null,
    val playerId: String? = null,
    val verified: Boolean = false
)

data class Conversation(
    val id: Long,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val recipientName: String? = null,
    val recipientAddress: String? = null
)

data class LocalMessage(
    val id: String,
    val conversationId: Long,
    val text: String,
    val createdAt: Long,
    val status: String
)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "ua_messenger.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE contacts (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                destination TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                identity_hash TEXT,
                public_key TEXT,
                player_id TEXT,
                verified INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                contact_id TEXT REFERENCES contacts(id) ON DELETE SET NULL
            )""".trimIndent()
        )
        createMessagesTable(db)
        createUaIdentityTable(db)
        db.execSQL("CREATE INDEX messages_conversation_time ON messages(conversation_id, created_at)")
    }

    private fun createUaIdentityTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE ua_local_identity (
                singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                player_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                reticulum_identity_hash TEXT,
                reticulum_public_key TEXT,
                lxmf_destination TEXT,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    private fun createMessagesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                conversation_id INTEGER NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                nonce BLOB,
                ciphertext BLOB,
                lxmf_hash TEXT,
                remote_hash TEXT,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN nonce BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN ciphertext BLOB")
        }
        if (oldVersion < 3) {
            db.execSQL(
                """CREATE TABLE contacts (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    destination TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL
                )""".trimIndent()
            )
            db.execSQL("ALTER TABLE conversations ADD COLUMN contact_id TEXT REFERENCES contacts(id) ON DELETE SET NULL")
        }
        if (oldVersion < 4) {
            createUaIdentityTable(db)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN identity_hash TEXT")
            db.execSQL("ALTER TABLE contacts ADD COLUMN public_key TEXT")
            db.execSQL("ALTER TABLE contacts ADD COLUMN player_id TEXT")
            db.execSQL("ALTER TABLE contacts ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE messages ADD COLUMN lxmf_hash TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN remote_hash TEXT")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS messages_lxmf_hash ON messages(lxmf_hash) WHERE lxmf_hash IS NOT NULL")
        }
    }

    @Synchronized
    fun getOrCreateUaIdentity(): UaLocalIdentity {
        readableDatabase.rawQuery(
            "SELECT player_id, node_id, reticulum_identity_hash, reticulum_public_key, lxmf_destination FROM ua_local_identity WHERE singleton_id = 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return UaLocalIdentity(
                    cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4)
                )
            }
        }
        val identity = UaLocalIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val values = ContentValues().apply {
            put("singleton_id", 1)
            put("player_id", identity.playerId)
            put("node_id", identity.nodeId)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("ua_local_identity", null, values)
        return identity
    }

    @Synchronized
    fun bindReticulumIdentity(publicIdentityHash: String, publicKeyHex: String, destination: String): UaLocalIdentity {
        require(Regex("^[0-9a-f]{32}$").matches(publicIdentityHash)) { "Invalid Reticulum identity hash." }
        require(publicKeyHex.matches(Regex("^(?:[0-9a-f]{2})+$"))) { "Invalid Reticulum public key." }
        require(Regex("^[0-9a-f]{32}$").matches(destination)) { "Invalid LXMF destination." }
        val current = getOrCreateUaIdentity()
        if (current.reticulumIdentityHash != null && current.reticulumIdentityHash != publicIdentityHash) {
            error("The saved UA node is already bound to a different Reticulum identity. Refusing silent identity replacement.")
        }
        if (current.reticulumPublicKey != null && current.reticulumPublicKey != publicKeyHex) {
            error("Reticulum public key does not match the saved node binding.")
        }
        if (current.lxmfDestination != null && current.lxmfDestination != destination) {
            error("LXMF destination does not match the saved node binding.")
        }
        val values = ContentValues().apply {
            put("reticulum_identity_hash", publicIdentityHash)
            put("reticulum_public_key", publicKeyHex)
            put("lxmf_destination", destination)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("ua_local_identity", values, "singleton_id = 1", null)
        return current.copy(
            reticulumIdentityHash = publicIdentityHash,
            reticulumPublicKey = publicKeyHex,
            lxmfDestination = destination
        )
    }

    fun addContact(name: String, destinationInput: String): String {
        val cleanName = name.trim()
        val destination = destinationInput.trim().lowercase()
        require(cleanName.isNotEmpty()) { "Enter a contact name." }
        require(Regex("^[0-9a-f]{32}$").matches(destination)) {
            "Enter a 32-character hexadecimal LXMF destination."
        }
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("name", cleanName)
            put("destination", destination)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("contacts", null, values)
        return id
    }

    @Synchronized
    fun addVerifiedContact(name: String, destinationInput: String, identityHash: String, publicKey: String, playerId: String?): String {
        val cleanName = name.trim()
        val destination = destinationInput.trim().lowercase()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "Enter a valid contact name." }
        require(Regex("^[0-9a-f]{32}$").matches(destination)) { "Invalid LXMF destination." }
        require(Regex("^[0-9a-f]{32}$").matches(identityHash)) { "Invalid Reticulum identity hash." }
        require(Regex("^(?:[0-9a-f]{2}){64}$").matches(publicKey)) { "Invalid Reticulum public key." }
        val existing = readableDatabase.rawQuery(
            "SELECT id, identity_hash, public_key FROM contacts WHERE destination = ?",
            arrayOf(destination)
        ).use { cursor ->
            if (cursor.moveToFirst()) Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)) else null
        }
        if (existing != null) {
            if (existing.second == null && existing.third == null) {
                val upgrade = ContentValues().apply {
                    put("name", cleanName)
                    put("identity_hash", identityHash)
                    put("public_key", publicKey)
                    if (playerId == null) putNull("player_id") else put("player_id", playerId)
                    put("verified", 1)
                }
                writableDatabase.update("contacts", upgrade, "id = ?", arrayOf(existing.first))
                return existing.first
            }
            require(existing.second == identityHash && existing.third == publicKey) {
                "This LXMF address is already saved with a different identity. No changes were made."
            }
            return existing.first
        }
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("name", cleanName)
            put("destination", destination)
            put("created_at", System.currentTimeMillis())
            put("identity_hash", identityHash)
            put("public_key", publicKey)
            if (playerId == null) putNull("player_id") else put("player_id", playerId)
            put("verified", 1)
        }
        writableDatabase.insertOrThrow("contacts", null, values)
        return id
    }

    fun listContacts(): List<Contact> {
        val result = mutableListOf<Contact>()
        readableDatabase.rawQuery(
            "SELECT id, name, destination, created_at, identity_hash, public_key, player_id, verified FROM contacts ORDER BY name COLLATE NOCASE",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Contact(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                    cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getInt(7) == 1
                )
            }
        }
        return result
    }

    fun createConversation(title: String): Long = insertConversation(title, null)

    fun createConversationForContact(contactId: String): Long {
        val contact = listContacts().firstOrNull { it.id == contactId }
            ?: error("Contact no longer exists.")
        return insertConversation(contact.name, contact.id)
    }

    private fun insertConversation(title: String, contactId: String?): Long {
        val values = ContentValues().apply {
            put("title", title.trim())
            put("created_at", System.currentTimeMillis())
            if (contactId == null) putNull("contact_id") else put("contact_id", contactId)
        }
        return writableDatabase.insertOrThrow("conversations", null, values)
    }

    fun listConversations(): List<Conversation> {
        val result = mutableListOf<Conversation>()
        readableDatabase.rawQuery(
            """SELECT c.id, c.title, c.created_at,
                (SELECT MAX(m.created_at) FROM messages m WHERE m.conversation_id = c.id) AS latest,
                p.name, p.destination
                FROM conversations c LEFT JOIN contacts p ON p.id = c.contact_id
                ORDER BY COALESCE(latest, c.created_at) DESC""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val preview = latestMessagePreview(id)
                result += Conversation(
                    id, cursor.getString(1), preview, cursor.getLong(2),
                    cursor.getString(4), cursor.getString(5)
                )
            }
        }
        return result
    }

    private fun latestMessagePreview(conversationId: Long): String {
        val message = listMessages(conversationId).lastOrNull() ?: return "No messages yet"
        return message.text
    }

    fun addQueuedMessage(conversationId: Long, text: String): String {
        require(text.isNotBlank()) { "Message cannot be empty." }
        val recipient = readableDatabase.rawQuery(
            """SELECT p.destination
                FROM conversations c
                LEFT JOIN contacts p ON p.id = c.contact_id
                WHERE c.id = ?""",
            arrayOf(conversationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        require(recipient != null && Regex("^[0-9a-f]{32}$").matches(recipient)) {
            "A valid saved LXMF recipient is required before a message can be queued."
        }
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


    data class PendingOutbound(val id: String, val recipient: String, val text: String)

    @Synchronized
    fun pendingOutbound(limit: Int = 20): List<PendingOutbound> {
        val result = mutableListOf<PendingOutbound>()
        readableDatabase.rawQuery(
            """SELECT m.id, p.destination, m.nonce, m.ciphertext
                FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                JOIN contacts p ON p.id = c.contact_id
                WHERE m.status IN ('QUEUED', 'WAITING_FOR_ROUTE', 'WAITING_FOR_IDENTITY')
                ORDER BY m.created_at ASC LIMIT ?""",
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PendingOutbound(
                    cursor.getString(0), cursor.getString(1),
                    LocalMessageCrypto.decrypt(cursor.getBlob(2), cursor.getBlob(3))
                )
            }
        }
        return result
    }

    @Synchronized
    fun updateOutbound(id: String, status: String, lxmfHash: String? = null) {
        val allowed = setOf("QUEUED", "WAITING_FOR_ROUTE", "WAITING_FOR_IDENTITY", "SUBMITTED", "SENT", "DELIVERED", "FAILED", "REJECTED")
        require(status in allowed) { "Unsupported message state." }
        val values = ContentValues().apply {
            put("status", status)
            if (lxmfHash != null) put("lxmf_hash", lxmfHash)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun updateByLxmfHash(lxmfHash: String, status: String) {
        val allowed = setOf("SUBMITTED", "SENT", "DELIVERED", "FAILED", "REJECTED")
        require(status in allowed) { "Unsupported delivery state." }
        writableDatabase.update(
            "messages", ContentValues().apply { put("status", status) },
            "lxmf_hash = ?", arrayOf(lxmfHash)
        )
    }

    @Synchronized
    fun saveInbound(source: String, lxmfHash: String, text: String, timestamp: Long): Boolean {
        require(Regex("^[0-9a-f]{32}$").matches(source)) { "Invalid inbound LXMF source." }
        require(lxmfHash.isNotBlank()) { "Inbound LXMF message has no deduplication hash." }
        val exists = readableDatabase.rawQuery(
            "SELECT id FROM messages WHERE lxmf_hash = ?",
            arrayOf(lxmfHash)
        ).use { it.moveToFirst() }
        if (exists) return false
        val contact = readableDatabase.rawQuery(
            "SELECT id, name FROM contacts WHERE destination = ?",
            arrayOf(source)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        }
        val conversationId = if (contact != null) {
            readableDatabase.rawQuery(
                "SELECT id FROM conversations WHERE contact_id = ? ORDER BY id LIMIT 1",
                arrayOf(contact.first)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else insertConversation(contact.second, contact.first)
            }
        } else {
            insertConversation("Message request · ${source.take(8)}", null)
        }
        val sealed = LocalMessageCrypto.encrypt(text)
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("conversation_id", conversationId)
            put("body", "")
            put("created_at", timestamp)
            put("status", if (contact != null) "RECEIVED" else "REQUEST")
            put("nonce", sealed.nonce)
            put("ciphertext", sealed.ciphertext)
            put("lxmf_hash", lxmfHash)
            put("remote_hash", source)
        }
        writableDatabase.insertOrThrow("messages", null, values)
        return true
    }

    fun addLocalMessage(conversationId: Long, text: String): String {
        require(text.isNotBlank()) { "Note cannot be empty." }
        val exists = readableDatabase.rawQuery(
            "SELECT id FROM conversations WHERE id = ?",
            arrayOf(conversationId.toString())
        ).use { it.moveToFirst() }
        require(exists) { "Conversation no longer exists." }
        val sealed = LocalMessageCrypto.encrypt(text)
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("conversation_id", conversationId)
            put("body", "")
            put("created_at", System.currentTimeMillis())
            put("status", "LOCAL_ONLY")
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
