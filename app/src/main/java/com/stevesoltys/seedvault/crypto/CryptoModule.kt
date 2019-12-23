package com.stevesoltys.seedvault.crypto

import org.koin.dsl.module

val cryptoModule = module {
    factory<CipherFactory> { CipherFactoryImpl(get()) }
    single<KeyManager> { KeyManagerImpl() }
    single<Crypto> { CryptoImpl(get(), get(), get()) }
}
