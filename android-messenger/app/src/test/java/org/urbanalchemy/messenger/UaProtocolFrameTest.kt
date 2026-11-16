package org.urbanalchemy.messenger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UaProtocolFrameTest {
    @Test fun sessionOfferFrameRoundTrips() {
        val original = UaProtocolFrame(UaProtocolFrame.Type.SESSION_OFFER, byteArrayOf(1, 2, 3, 4))
        val decoded = UaProtocolFrame.decode(original.encode())
        assertEquals(UaProtocolFrame.Type.SESSION_OFFER, decoded.type)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test fun supportsEncryptedMessagesAndDeliveryReceipts() {
        for (type in listOf(UaProtocolFrame.Type.ENCRYPTED_MESSAGE, UaProtocolFrame.Type.DELIVERY_ACKNOWLEDGEMENT)) {
            val original = UaProtocolFrame(type, ByteArray(128) { it.toByte() })
            val decoded = UaProtocolFrame.decode(original.encode())
            assertEquals(type, decoded.type)
            assertArrayEquals(original.payload, decoded.payload)
        }
    }

    @Test fun payloadIsDefensivelyCopied() {
        val source = byteArrayOf(1, 2, 3)
        val frame = UaProtocolFrame(UaProtocolFrame.Type.SESSION_OFFER, source)
        source[0] = 9
        val returned = frame.payload
        returned[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.payload)
    }

    @Test fun rejectsUnknownVersionTypeAndTrailingBytes() {
        val encoded = UaProtocolFrame(UaProtocolFrame.Type.SESSION_OFFER, byteArrayOf(1)).encode()
        assertThrows(IllegalArgumentException::class.java) {
            UaProtocolFrame.decode(encoded.clone().also { it[4] = 0; it[7] = 2 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            UaProtocolFrame.decode(encoded.clone().also { it[8] = 99 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            UaProtocolFrame.decode(encoded + byteArrayOf(0))
        }
    }

    @Test fun rejectsEmptyOrOversizedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            UaProtocolFrame(UaProtocolFrame.Type.SESSION_OFFER, byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UaProtocolFrame(UaProtocolFrame.Type.SESSION_OFFER, ByteArray(UaProtocolFrame.MAX_PAYLOAD_BYTES + 1))
        }
    }
}
