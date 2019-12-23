package com.stevesoltys.seedvault.crypto

import javax.crypto.Cipher
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.Cipher.ENCRYPT_MODE
import javax.crypto.spec.GCMParameterSpec

private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_AUTHENTICATION_TAG_LENGTH = 128

interface CipherFactory {
    fun createEncryptionCipher(): Cipher
    fun createDecryptionCipher(iv: ByteArray): Cipher
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

}
