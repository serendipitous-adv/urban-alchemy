package org.urbanalchemy.messenger

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalQueuedMessageCodecTest {
    @Before fun useJvmTestKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        LocalMessageCrypto.keyProvider = { key }
    }

    @After fun restoreAndroidKeystore() {
        LocalMessageCrypto.keyProvider = { throw IllegalStateException("Android Keystore is unavailable in plain JVM tests.") }
    }

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
