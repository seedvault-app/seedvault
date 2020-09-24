package com.stevesoltys.seedvault.crypto

import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import android.security.keystore.KeyProtection
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal const val KEY_SIZE = 256
private const val KEY_SIZE_BYTES = KEY_SIZE / 8
private const val KEY_ALIAS = "com.stevesoltys.seedvault"
private const val ANDROID_KEY_STORE = "AndroidKeyStore"

interface KeyManager {
    /**
     * Store a new backup key derived from the given [seed].
     *
     * The seed needs to be larger or equal to [KEY_SIZE_BYTES].
     */
    fun storeBackupKey(seed: ByteArray)

    /**
     * @return true if a backup key already exists in the [KeyStore].
     */
    fun hasBackupKey(): Boolean

    /**
     * Returns the backup key, so it can be used for encryption or decryption.
     *
     * Note that any attempt to export the key will return null or an empty [ByteArray],
     * because the key can not leave the [KeyStore]'s hardware security module.
     */
    fun getBackupKey(): SecretKey
}

internal class KeyManagerImpl : KeyManager {

    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
    }

    override fun storeBackupKey(seed: ByteArray) {
        if (seed.size < KEY_SIZE_BYTES) throw IllegalArgumentException()
        // TODO check if using first 256 of 512 bits produced by PBKDF2WithHmacSHA512 is safe!
        val secretKeySpec = SecretKeySpec(seed, 0, KEY_SIZE_BYTES, "AES")
        val ksEntry = SecretKeyEntry(secretKeySpec)
        keyStore.setEntry(KEY_ALIAS, ksEntry, getKeyProtection())
    }

    override fun hasBackupKey() = keyStore.containsAlias(KEY_ALIAS) &&
        keyStore.entryInstanceOf(KEY_ALIAS, SecretKeyEntry::class.java)

    override fun getBackupKey(): SecretKey {
        val ksEntry = keyStore.getEntry(KEY_ALIAS, null) as SecretKeyEntry
        return ksEntry.secretKey
    }

    private fun getKeyProtection(): KeyProtection {
        val builder = KeyProtection.Builder(PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
            .setBlockModes(BLOCK_MODE_GCM)
            .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        // unlocking is required only for decryption, so when restoring from backup
        builder.setUnlockedDeviceRequired(true)
        return builder.build()
    }

}
