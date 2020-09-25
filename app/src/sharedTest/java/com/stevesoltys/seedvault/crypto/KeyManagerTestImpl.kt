package com.stevesoltys.seedvault.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeyManagerTestImpl : KeyManager {

    private val key by lazy {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(KEY_SIZE)
        keyGenerator.generateKey()
    }

    override fun storeBackupKey(seed: ByteArray) {
        throw NotImplementedError("not implemented")
    }

    override fun hasBackupKey(): Boolean {
        return true
    }

    override fun getBackupKey(): SecretKey {
        return key
    }

}
