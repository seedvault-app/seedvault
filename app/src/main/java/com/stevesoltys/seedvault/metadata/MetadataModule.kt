package com.stevesoltys.seedvault.metadata

import org.koin.dsl.module

val metadataModule = module {
    single<MetadataWriter> { MetadataWriterImpl(get()) }
    single<MetadataReader> { MetadataReaderImpl(get()) }
}
