package com.stevesoltys.seedvault.header

import org.koin.dsl.module

val headerModule = module {
    single<HeaderReader> { HeaderReaderImpl() }
}
