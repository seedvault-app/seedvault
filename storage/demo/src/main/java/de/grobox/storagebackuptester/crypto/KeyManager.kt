package de.grobox.storagebackuptester.crypto

import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import android.security.keystore.KeyProperties.PURPOSE_SIGN
import android.security.keystore.KeyProperties.PURPOSE_VERIFY
import android.security.keystore.KeyProtection
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object KeyManager {

    private const val KEY_SIZE = 256
    internal const val KEY_SIZE_BYTES = KEY_SIZE / 8
    private const val KEY_ALIAS_MASTER = "com.stevesoltys.seedvault.master"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val ALGORITHM_HMAC = "HmacSHA256"

    private const val FAKE_SEED = "This is a legacy backup key 1234"

    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
    }

    fun storeMasterKey() {
        val seed = FAKE_SEED.toByteArray()
        storeMasterKey(seed)
    }

    private fun storeMasterKey(seed: ByteArray) {
        if (seed.size < KEY_SIZE_BYTES) throw IllegalArgumentException()
        val secretKeySpec = SecretKeySpec(seed, 0, KEY_SIZE_BYTES, ALGORITHM_HMAC)
        val ksEntry = KeyStore.SecretKeyEntry(secretKeySpec)
        keyStore.setEntry(KEY_ALIAS_MASTER, ksEntry, getKeyProtection())
    }

    fun hasMasterKey(): Boolean = keyStore.containsAlias(KEY_ALIAS_MASTER)

    fun getMasterKey(): SecretKey {
        val ksEntry = keyStore.getEntry(KEY_ALIAS_MASTER, null) as KeyStore.SecretKeyEntry
        return ksEntry.secretKey
    }

    private fun getKeyProtection(): KeyProtection {
        val builder =
            KeyProtection.Builder(PURPOSE_ENCRYPT or PURPOSE_DECRYPT or PURPOSE_SIGN or PURPOSE_VERIFY)
        return builder.build()
    }

}
