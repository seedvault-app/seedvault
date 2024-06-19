/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val metadataModule = module {
    single { MetadataManager(androidContext(), get(), get(), get(), get(), get(), get()) }
    single<MetadataWriter> { MetadataWriterImpl(get()) }
    single<MetadataReader> { MetadataReaderImpl(get()) }
}
