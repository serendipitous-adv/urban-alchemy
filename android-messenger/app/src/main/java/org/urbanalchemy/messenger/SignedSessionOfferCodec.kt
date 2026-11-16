package org.urbanalchemy.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded binary wire codec for signed ephemeral session offers. */
object SignedSessionOfferCodec {
    private const val VERSION = 1
    private const val MAX_ENCODED_BYTES = 8_192
    private const val MAX_KEY_BYTES = 1_024
    private const val MIN_NONCE_BYTES = 16
    private const val MAX_NONCE_BYTES = 64
    private const val MIN_SIGNATURE_BYTES = 8
    private const val MAX_SIGNATURE_BYTES = 512
    private val MAGIC = byteArrayOf(0x55, 0x41, 0x53, 0x4f) // UASO

    fun encode(offer: SignedSessionHandshake.Offer): ByteArray {
        require(offer.sessionId.isNotBlank() && offer.sessionId.length <= 256)
        require(offer.senderFingerprint.isNotBlank() && offer.senderFingerprint.length <= 512)
        require(offer.recipientFingerprint.isNotBlank() && offer.recipientFingerprint.length <= 512)
        require(offer.senderFingerprint != offer.recipientFingerprint)
        require(offer.ephemeralPublicKey.size in 1..MAX_KEY_BYTES)
        require(offer.nonce.size in MIN_NONCE_BYTES..MAX_NONCE_BYTES)
        require(offer.issuedAtEpochMillis >= 0 && offer.expiresAtEpochMillis > offer.issuedAtEpochMillis)
        require(offer.signature.size in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES)

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(offer.sessionId)
            out.writeUTF(offer.senderFingerprint)
            out.writeUTF(offer.recipientFingerprint)
            writeBytes(out, offer.ephemeralPublicKey)
            writeBytes(out, offer.nonce)
            out.writeLong(offer.issuedAtEpochMillis)
            out.writeLong(offer.expiresAtEpochMillis)
            writeBytes(out, offer.signature)
        }
        return bytes.toByteArray().also {
            require(it.size <= MAX_ENCODED_BYTES) { "Session offer exceeds the wire size limit." }
        }
    }

    fun decode(encoded: ByteArray): SignedSessionHandshake.Offer {
        require(encoded.size in (MAGIC.size + 4 + 1)..MAX_ENCODED_BYTES) {
            "Session offer size is outside the allowed range."
        }
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Unrecognized session offer." }
            require(input.readInt() == VERSION) { "Unsupported session offer version." }
            val sessionId = input.readUTF()
            val sender = input.readUTF()
            val recipient = input.readUTF()
            require(sessionId.isNotBlank() && sessionId.length <= 256)
            require(sender.isNotBlank() && sender.length <= 512)
            require(recipient.isNotBlank() && recipient.length <= 512 && recipient != sender)
            val ephemeralKey = readBytes(input, 1..MAX_KEY_BYTES)
            val nonce = readBytes(input, MIN_NONCE_BYTES..MAX_NONCE_BYTES)
            val issuedAt = input.readLong()
            val expiresAt = input.readLong()
            require(issuedAt >= 0 && expiresAt > issuedAt) { "Invalid session offer lifetime." }
            val signature = readBytes(input, MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES)
            require(input.read() == -1) { "Unexpected trailing session offer data." }
            return SignedSessionHandshake.Offer(
                sessionId, sender, recipient, ephemeralKey, nonce,
                issuedAt, expiresAt, signature
            )
        }
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private fun readBytes(input: DataInputStream, range: IntRange): ByteArray {
        val size = input.readInt()
        require(size in range) { "Invalid session offer field length." }
        return ByteArray(size).also(input::readFully)
    }
}
