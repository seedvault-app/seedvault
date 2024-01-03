package com.stevesoltys.seedvault.service.metadata

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val metadataModule = module {
    single { MetadataService(androidContext(), get(), get(), get(), get(), get()) }
    single<MetadataWriter> { MetadataWriterImpl(get()) }
    single<MetadataReader> { MetadataReaderImpl(get()) }
}
