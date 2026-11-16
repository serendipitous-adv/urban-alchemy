package org.urbanalchemy.messenger

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey

data class LocalIdentity(val fingerprint: String)

/**
 * Owns the long-lived device signing identity.
 * The private key remains non-exportable in Android Keystore; callers receive
 * only a PrivateKey handle that can be passed to Signature.initSign().
 */
object DeviceIdentity {
    private const val ALIAS = "ua-messenger-device-identity"

    /** Return the persistent signing pair without exporting private-key bytes. */
    fun getSigningKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }

        val certificate = requireNotNull(keyStore.getCertificate(ALIAS)) {
            "Device signing identity certificate is missing."
        }
        val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey
            ?: error("Device signing private-key handle is unavailable.")
        return KeyPair(certificate.publicKey, privateKey)
    }

    fun getOrCreate(): LocalIdentity {
        val publicKey = getSigningKeyPair().public
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        val fingerprint = digest.take(16).joinToString(":") { "%02x".format(it) }
        return LocalIdentity(fingerprint)
    }
}
