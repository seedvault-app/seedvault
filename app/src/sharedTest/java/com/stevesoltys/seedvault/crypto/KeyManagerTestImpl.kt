/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeyManagerTestImpl(private val customKey: SecretKey? = null) : KeyManager {

    private val key: SecretKey by lazy {
        customKey ?: run {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(KEY_SIZE)
            keyGenerator.generateKey()
        }
    }

    override fun storeBackupKey(seed: ByteArray) {
        throw NotImplementedError("not implemented")
    }

    override fun storeMainKey(seed: ByteArray) {
        throw NotImplementedError("not implemented")
    }

    override fun hasBackupKey(): Boolean {
        return true
    }

    override fun hasMainKey(): Boolean {
        throw NotImplementedError("not implemented")
    }

    override fun getBackupKey(): SecretKey = key

    override fun getMainKey(): SecretKey = key

}
