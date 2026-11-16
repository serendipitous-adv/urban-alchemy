package org.urbanalchemy.messenger

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/** A shareable public-only identity bundle for explicit out-of-band peer pinning. */
data class PeerIdentityBundle(val fingerprint: String, val signingPublicKey: PublicKey) {
    fun encode(): String = listOf(
        MAGIC,
        fingerprint,
        Base64.encodeToString(signingPublicKey.encoded, Base64.NO_WRAP)
    ).joinToString("|")

    companion object {
        private const val MAGIC = "UAID1"

        fun fromPublicKey(publicKey: PublicKey): PeerIdentityBundle =
            PeerIdentityBundle(SignedSessionHandshake.fingerprint(publicKey), publicKey)

        fun decode(value: String): PeerIdentityBundle {
            val parts = value.trim().split("|")
            require(parts.size == 3 && parts[0] == MAGIC) { "Invalid Urban Alchemy identity bundle." }
            val fingerprint = parts[1]
            require(Regex("^(?:[0-9a-f]{2}:){15}[0-9a-f]{2}$").matches(fingerprint)) {
                "Invalid identity fingerprint."
            }
            val encoded = try {
                Base64.decode(parts[2], Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid public-key encoding.")
            }
            require(encoded.isNotEmpty() && encoded.size <= MAX_PUBLIC_KEY_BYTES) {
                "Public key is outside the allowed size."
            }
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            require(SignedSessionHandshake.fingerprint(key) == fingerprint) {
                "Identity fingerprint does not match its public key."
            }
            return PeerIdentityBundle(fingerprint, key)
        }

        private const val MAX_PUBLIC_KEY_BYTES = 1024
    }
}

/**
 * Stores only explicitly imported, fingerprint-verified peer public keys.
 * Importing a bundle is an out-of-band trust decision; this class never silently
 * trusts a key received over the same unauthenticated socket it will authenticate.
 */
class PeerIdentityPinStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ua_pinned_peer_identities", Context.MODE_PRIVATE
    )

    fun pin(bundleText: String): PeerIdentityBundle {
        val bundle = PeerIdentityBundle.decode(bundleText)
        preferences.edit()
            .putString(bundle.fingerprint, Base64.encodeToString(bundle.signingPublicKey.encoded, Base64.NO_WRAP))
            .apply()
        return bundle
    }

    fun get(fingerprint: String): PeerIdentityBundle? {
        val encoded = preferences.getString(fingerprint, null) ?: return null
        val keyBytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val bundle = PeerIdentityBundle.fromPublicKey(key)
            if (bundle.fingerprint == fingerprint) bundle else null
        } catch (_: Exception) {
            null
        }
    }

    fun listFingerprints(): Set<String> = preferences.all.keys.toSet()

    fun unpin(fingerprint: String) {
        preferences.edit().remove(fingerprint).apply()
    }
}
