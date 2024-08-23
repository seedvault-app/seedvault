/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.crypto

import android.security.keystore.KeyProperties.BLOCK_MODE_GCM
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
import android.security.keystore.KeyProperties.PURPOSE_DECRYPT
import android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
import android.security.keystore.KeyProperties.PURPOSE_SIGN
import android.security.keystore.KeyProperties.PURPOSE_VERIFY
import android.security.keystore.KeyProtection
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal const val KEY_SIZE = 256
internal const val KEY_SIZE_BYTES = KEY_SIZE / 8
internal const val KEY_ALIAS_BACKUP = "com.stevesoltys.seedvault"
internal const val KEY_ALIAS_MAIN = "com.stevesoltys.seedvault.main"
private const val KEY_ALGORITHM_BACKUP = "AES"
private const val KEY_ALGORITHM_MAIN = "HmacSHA256"

interface KeyManager : org.calyxos.seedvault.core.crypto.KeyManager {
    /**
     * Store a new backup key derived from the given [seed].
     *
     * The seed needs to be larger or equal to [KEY_SIZE_BYTES].
     */
    fun storeBackupKey(seed: ByteArray)

    /**
     * Store a new main key derived from the given [seed].
     *
     * The seed needs to be larger or equal to two times [KEY_SIZE_BYTES]
     * and is usually the same as for [storeBackupKey].
     */
    fun storeMainKey(seed: ByteArray)

    /**
     * @return true if a backup key already exists in the [KeyStore].
     */
    fun hasBackupKey(): Boolean

    /**
     * @return true if a main key already exists in the [KeyStore].
     */
    fun hasMainKey(): Boolean

    /**
     * Returns the backup key, so it can be used for encryption or decryption.
     *
     * Note that any attempt to export the key will return null or an empty [ByteArray],
     * because the key can not leave the [KeyStore]'s hardware security module.
     */
    fun getBackupKey(): SecretKey
}

internal class KeyManagerImpl(
    private val keyStore: KeyStore,
) : KeyManager {

    override fun storeBackupKey(seed: ByteArray) {
        if (seed.size < KEY_SIZE_BYTES) throw IllegalArgumentException()
        val backupKeyEntry =
            SecretKeyEntry(SecretKeySpec(seed, 0, KEY_SIZE_BYTES, KEY_ALGORITHM_BACKUP))
        keyStore.setEntry(KEY_ALIAS_BACKUP, backupKeyEntry, getBackupKeyProtection())
    }

    override fun storeMainKey(seed: ByteArray) {
        if (seed.size < KEY_SIZE_BYTES * 2) throw IllegalArgumentException()
        val mainKeyEntry =
            SecretKeyEntry(SecretKeySpec(seed, KEY_SIZE_BYTES, KEY_SIZE_BYTES, KEY_ALGORITHM_MAIN))
        keyStore.setEntry(KEY_ALIAS_MAIN, mainKeyEntry, getMainKeyProtection())
    }

    override fun hasBackupKey() = keyStore.containsAlias(KEY_ALIAS_BACKUP)

    override fun hasMainKey() = keyStore.containsAlias(KEY_ALIAS_MAIN)

    override fun getBackupKey(): SecretKey {
        val ksEntry = keyStore.getEntry(KEY_ALIAS_BACKUP, null) as SecretKeyEntry
        return ksEntry.secretKey
    }

    override fun getMainKey(): SecretKey {
        val ksEntry = keyStore.getEntry(KEY_ALIAS_MAIN, null) as SecretKeyEntry
        return ksEntry.secretKey
    }

    private fun getBackupKeyProtection(): KeyProtection {
        val builder = KeyProtection.Builder(PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
            .setBlockModes(BLOCK_MODE_GCM)
            .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        // unlocking is required only for decryption, so when restoring from backup
        // FIXME disabled for Android 12 GSI as it crashes when importing the key
        //  KeyStoreException: Failed to import secret key.
        // builder.setUnlockedDeviceRequired(true)
        return builder.build()
    }

    private fun getMainKeyProtection(): KeyProtection {
        // let's not lock down the main key too much, because we have no second chance
        // and don't want to repeat the issue with the locked down backup key
        val builder = KeyProtection.Builder(
            PURPOSE_ENCRYPT or PURPOSE_DECRYPT or
                PURPOSE_SIGN or PURPOSE_VERIFY
        )
        return builder.build()
    }

}
