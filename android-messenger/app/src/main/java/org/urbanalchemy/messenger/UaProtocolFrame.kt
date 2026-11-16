package org.urbanalchemy.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Versioned UA application frame carried unchanged by Wi-Fi Direct sockets or
 * any future transport adapter. Cryptographic verification belongs to the
 * message/session handlers, never to this framing codec.
 */
class UaProtocolFrame(type: Type, payload: ByteArray) {
    enum class Type(val wireValue: Int) {
        SESSION_OFFER(1),
        ENCRYPTED_MESSAGE(2),
        DELIVERY_ACKNOWLEDGEMENT(3);

        companion object {
            fun fromWire(value: Int): Type =
                values().firstOrNull { it.wireValue == value }
                    ?: throw IllegalArgumentException("Unknown UA frame type.")
        }
    }

    val type: Type = type
    private val body = payload.copyOf()
    val payload: ByteArray get() = body.copyOf()

    init {
        require(body.isNotEmpty()) { "UA frame payload cannot be empty." }
        require(body.size <= MAX_PAYLOAD_BYTES) { "UA frame payload is too large." }
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream(HEADER_BYTES + body.size)
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeInt(VERSION)
            out.writeByte(type.wireValue)
            out.writeInt(body.size)
            out.write(body)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = SocketFrameTransport.MAX_FRAME_BYTES
        private const val VERSION = 1
        private const val HEADER_BYTES = 13
        private val MAGIC = byteArrayOf(0x55, 0x41, 0x50, 0x46) // UAPF

        fun decode(encoded: ByteArray): UaProtocolFrame {
            require(encoded.size in (HEADER_BYTES + 1)..(HEADER_BYTES + MAX_PAYLOAD_BYTES)) {
                "UA frame size is outside the allowed range."
            }
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "Unrecognized UA protocol frame." }
                require(input.readInt() == VERSION) { "Unsupported UA protocol version." }
                val type = Type.fromWire(input.readUnsignedByte())
                val length = input.readInt()
                require(length in 1..MAX_PAYLOAD_BYTES) { "Invalid UA frame payload length." }
                require(length == input.available()) { "UA frame payload length mismatch or trailing bytes." }
                val payload = ByteArray(length).also(input::readFully)
                return UaProtocolFrame(type, payload)
            }
        }
    }
}
