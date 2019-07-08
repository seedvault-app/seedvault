package com.stevesoltys.backup.security

import android.os.Build.VERSION.SDK_INT
import android.security.keystore.KeyProperties.*
import android.security.keystore.KeyProtection
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val KEY_SIZE = 256
private const val KEY_ALIAS = "com.stevesoltys.backup"
private const val ANDROID_KEY_STORE = "AndroidKeyStore"

object KeyManager {

    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
    }

    fun storeBackupKey(seed: ByteArray) {
        if (seed.size < KEY_SIZE / 8) throw IllegalArgumentException()
        // TODO check if using first 256 of 512 bytes produced by PBKDF2WithHmacSHA512 is safe!
        val secretKeySpec = SecretKeySpec(seed, 0, KEY_SIZE / 8, "AES")
        val ksEntry = SecretKeyEntry(secretKeySpec)
        keyStore.setEntry(KEY_ALIAS, ksEntry, getKeyProtection())
    }

    fun hasBackupKey() = keyStore.containsAlias(KEY_ALIAS) &&
            keyStore.entryInstanceOf(KEY_ALIAS, SecretKeyEntry::class.java)

    fun getBackupKey(): SecretKey {
        val ksEntry = keyStore.getEntry(KEY_ALIAS, null) as SecretKeyEntry
        return ksEntry.secretKey
    }

    private fun getKeyProtection(): KeyProtection {
        val builder = KeyProtection.Builder(PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
                .setBlockModes(BLOCK_MODE_GCM)
                .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
        if (SDK_INT >= 28) builder.setUnlockedDeviceRequired(true)
        return builder.build()
    }

}
