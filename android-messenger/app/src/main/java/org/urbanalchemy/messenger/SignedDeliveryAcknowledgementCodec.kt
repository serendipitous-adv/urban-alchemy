package org.urbanalchemy.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Strict, versioned wire codec for signed delivery receipts.
 *
 * This codec only serializes/deserializes. Call SignedDeliveryAcknowledgement.verify
 * with the independently pinned recipient key before applying a decoded receipt.
 */
object SignedDeliveryAcknowledgementCodec {
    private const val VERSION = 1
    private const val MAX_WIRE_BYTES = 4_096
    private const val MAX_TEXT_BYTES = 512
    private const val MIN_SIGNATURE_BYTES = 8
    private const val MAX_SIGNATURE_BYTES = 512

    fun encode(acknowledgement: SignedDeliveryAcknowledgement): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(VERSION)
            writeText(data, acknowledgement.messageId, 128)
            writeText(data, acknowledgement.originalSenderFingerprint, MAX_TEXT_BYTES)
            writeText(data, acknowledgement.originalRecipientFingerprint, MAX_TEXT_BYTES)
            data.writeLong(acknowledgement.acknowledgedAtEpochMillis)
            writeText(data, acknowledgement.signerFingerprint, MAX_TEXT_BYTES)
            val signature = acknowledgement.signature
            require(signature.size in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) { "Invalid acknowledgement signature length." }
            data.writeInt(signature.size)
            data.write(signature)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_WIRE_BYTES) { "Acknowledgement exceeds maximum wire size." }
        }
    }

    fun encodeBase64(acknowledgement: SignedDeliveryAcknowledgement): String =
        Base64.getEncoder().encodeToString(encode(acknowledgement))

    fun decode(bytes: ByteArray): SignedDeliveryAcknowledgement {
        require(bytes.isNotEmpty() && bytes.size <= MAX_WIRE_BYTES) { "Invalid acknowledgement wire size." }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Unsupported acknowledgement version." }
        val messageId = readText(input, 128)
        val sender = readText(input, MAX_TEXT_BYTES)
        val recipient = readText(input, MAX_TEXT_BYTES)
        val timestamp = input.readLong()
        require(timestamp >= 0) { "Invalid acknowledgement timestamp." }
        val signer = readText(input, MAX_TEXT_BYTES)
        val signatureLength = input.readInt()
        require(signatureLength in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) { "Invalid acknowledgement signature length." }
        require(signatureLength <= input.available()) { "Truncated acknowledgement signature." }
        val signature = ByteArray(signatureLength)
        input.readFully(signature)
        require(input.available() == 0) { "Trailing acknowledgement data." }
        return SignedDeliveryAcknowledgement(
            messageId, sender, recipient, timestamp, signer, signature
        )
    }

    fun decodeBase64(encoded: String): SignedDeliveryAcknowledgement {
        require(encoded.isNotBlank() && encoded.length <= ((MAX_WIRE_BYTES + 2) / 3) * 4) {
            "Invalid acknowledgement Base64 size."
        }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid acknowledgement Base64.", error)
        }
        return decode(decoded)
    }

    private fun writeText(output: DataOutputStream, value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Acknowledgement text field exceeds limit." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        require(length in 1..maxBytes && length <= input.available()) { "Invalid acknowledgement text length." }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}
