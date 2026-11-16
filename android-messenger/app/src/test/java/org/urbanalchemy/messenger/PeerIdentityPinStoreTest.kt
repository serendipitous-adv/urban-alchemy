package org.urbanalchemy.messenger

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PeerIdentityPinStoreTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun exportedBundleRoundTripsAndPinsOnlyMatchingPublicKey() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val peer = keyPair()
        val bundleText = PeerIdentityBundle.fromPublicKey(peer.public).encode()
        val decoded = PeerIdentityBundle.decode(bundleText)
        assertEquals(SignedSessionHandshake.fingerprint(peer.public), decoded.fingerprint)
        assertArrayEquals(peer.public.encoded, decoded.signingPublicKey.encoded)

        val store = PeerIdentityPinStore(context)
        val pinned = store.pin(bundleText)
        assertEquals(decoded.fingerprint, pinned.fingerprint)
        assertArrayEquals(peer.public.encoded, store.get(decoded.fingerprint)?.signingPublicKey?.encoded)
        assertTrue(store.listFingerprints().contains(decoded.fingerprint))
        store.unpin(decoded.fingerprint)
        assertNull(store.get(decoded.fingerprint))
    }

    @Test fun rejectsTamperedFingerprintAndMalformedBundle() {
        val bundle = PeerIdentityBundle.fromPublicKey(keyPair().public).encode()
        val parts = bundle.split("|").toMutableList()
        parts[1] = "00:".repeat(16).dropLast(1)
        try {
            PeerIdentityBundle.decode(parts.joinToString("|"))
            fail("Mismatched fingerprint must be rejected.")
        } catch (_: IllegalArgumentException) { }
        try {
            PeerIdentityBundle.decode("UAID1|not-a-fingerprint|%%%")
            fail("Malformed bundle must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }
}
