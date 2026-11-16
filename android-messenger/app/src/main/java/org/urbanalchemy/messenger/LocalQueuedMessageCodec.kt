package org.urbanalchemy.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Device-keystore-encrypted queue payload, independent of any ephemeral peer session key. */
object LocalQueuedMessageCodec {
    private val MAGIC = byteArrayOf(0x55, 0x41, 0x51, 0x4d) // UAQM
    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val MAX_CIPHERTEXT_BYTES = 1_048_576

    fun seal(plaintext: String): String {
        require(plaintext.isNotBlank()) { "Message cannot be empty." }
        val sealed = LocalMessageCrypto.encrypt(plaintext)
        require(sealed.nonce.size == NONCE_BYTES)
        require(sealed.ciphertext.size in 16..MAX_CIPHERTEXT_BYTES)
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(MAGIC)
                out.writeByte(VERSION)
                out.writeByte(sealed.nonce.size)
                out.write(sealed.nonce)
                out.writeInt(sealed.ciphertext.size)
                out.write(sealed.ciphertext)
            }
            buffer.toByteArray()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun open(encoded: String): String {
        require(encoded.isNotBlank() && encoded.length <= ((MAX_CIPHERTEXT_BYTES + 64 + 2) / 3) * 4) {
            "Queued message payload is too large."
        }
        val bytes = try { Base64.getDecoder().decode(encoded) }
        catch (error: IllegalArgumentException) { throw IllegalArgumentException("Invalid queued message encoding.", error) }
        require(bytes.size <= MAX_CIPHERTEXT_BYTES + 64)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Unrecognized queued message payload." }
            require(input.readUnsignedByte() == VERSION) { "Unsupported queued message version." }
            val nonceSize = input.readUnsignedByte()
            require(nonceSize == NONCE_BYTES) { "Invalid local encryption nonce." }
            val nonce = ByteArray(nonceSize).also(input::readFully)
            val ciphertextSize = input.readInt()
            require(ciphertextSize in 16..MAX_CIPHERTEXT_BYTES && ciphertextSize == input.available()) {
                "Invalid local ciphertext length."
            }
            val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
            return LocalMessageCrypto.decrypt(nonce, ciphertext)
        }
    }
}
