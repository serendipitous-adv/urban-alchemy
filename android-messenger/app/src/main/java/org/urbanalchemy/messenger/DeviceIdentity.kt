package org.urbanalchemy.messenger

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest

data class LocalIdentity(val fingerprint: String)

object DeviceIdentity {
    private const val ALIAS = "ua-messenger-device-identity"

    fun getOrCreate(): LocalIdentity {
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
        val publicKey = keyStore.getCertificate(ALIAS).publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val fingerprint = digest.take(16).joinToString(":") { "%02x".format(it) }
        return LocalIdentity(fingerprint)
    }
}
