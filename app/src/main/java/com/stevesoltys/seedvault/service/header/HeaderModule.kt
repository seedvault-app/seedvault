package com.stevesoltys.seedvault.service.header

import org.koin.dsl.module

val headerModule = module {
    single<HeaderDecodeService> { HeaderDecodeServiceImpl() }
}
