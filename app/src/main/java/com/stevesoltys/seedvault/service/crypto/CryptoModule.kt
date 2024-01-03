package com.stevesoltys.seedvault.service.crypto

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
    single<CryptoService> { CryptoServiceImpl(get(), get(), get()) }
}
