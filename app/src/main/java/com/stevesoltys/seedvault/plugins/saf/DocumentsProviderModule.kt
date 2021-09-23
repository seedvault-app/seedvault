package com.stevesoltys.seedvault.plugins.saf

import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val documentsProviderModule = module {
    single { DocumentsStorage(androidContext(), get()) }

    single<StoragePlugin> { DocumentsProviderStoragePlugin(androidContext(), get()) }
    @Suppress("Deprecation")
    single<LegacyStoragePlugin> { DocumentsProviderLegacyPlugin(androidContext(), get()) }
}
