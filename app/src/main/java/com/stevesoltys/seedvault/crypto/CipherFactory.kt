/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.crypto

import java.security.Key
import javax.crypto.Cipher
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.Cipher.ENCRYPT_MODE
import javax.crypto.spec.GCMParameterSpec

private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
internal const val GCM_AUTHENTICATION_TAG_LENGTH = 128

interface CipherFactory {
    fun createEncryptionCipher(): Cipher
    fun createDecryptionCipher(iv: ByteArray): Cipher
    fun createEncryptionTestCipher(key: Key, iv: ByteArray): Cipher
}

internal class CipherFactoryImpl(private val keyManager: KeyManager) : CipherFactory {

    override fun createEncryptionCipher(): Cipher {
        return Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(ENCRYPT_MODE, keyManager.getBackupKey())
        }
    }

    override fun createDecryptionCipher(iv: ByteArray): Cipher {
        return Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            val spec = GCMParameterSpec(GCM_AUTHENTICATION_TAG_LENGTH, iv)
            init(DECRYPT_MODE, keyManager.getBackupKey(), spec)
        }
    }

    override fun createEncryptionTestCipher(key: Key, iv: ByteArray): Cipher {
        return Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            val params = GCMParameterSpec(GCM_AUTHENTICATION_TAG_LENGTH, iv)
            init(ENCRYPT_MODE, key, params)
        }
    }

}
