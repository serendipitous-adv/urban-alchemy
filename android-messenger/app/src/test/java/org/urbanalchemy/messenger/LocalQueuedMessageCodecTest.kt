package org.urbanalchemy.messenger

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalQueuedMessageCodecTest {
    @Test fun queuedPayloadCanBeOpenedIndependentlyOfPeerSessionKeys() {
        val sealed = LocalQueuedMessageCodec.seal("Survive a reconnect.")
        assertNotEquals("Survive a reconnect.", sealed)
        assertEquals("Survive a reconnect.", LocalQueuedMessageCodec.open(sealed))
    }

    @Test fun rejectsUnknownAndTamperedQueuePayloads() {
        try {
            LocalQueuedMessageCodec.open("bm90LWEtdWEta2V5")
            fail("Unknown queue format must be rejected.")
        } catch (_: IllegalArgumentException) { }
        val sealed = LocalQueuedMessageCodec.seal("Authenticated local storage.")
        val bytes = java.util.Base64.getDecoder().decode(sealed)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        try {
            LocalQueuedMessageCodec.open(tampered)
            fail("Tampered local ciphertext must fail authentication.")
        } catch (_: Exception) { }
    }
}
