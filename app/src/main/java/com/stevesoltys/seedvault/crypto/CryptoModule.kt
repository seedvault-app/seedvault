/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.crypto

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.security.KeyStore

const val ANDROID_KEY_STORE = "AndroidKeyStore"

val cryptoModule = module {
    factory<CipherFactory> { CipherFactoryImpl(get()) }
    single<KeyManager> {
        val keyStore by lazy {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
            }
        }
        KeyManagerImpl(keyStore)
    }
    single<Crypto> { CryptoImpl(androidContext(), get(), get(), get()) }
}
