package com.stevesoltys.seedvault.service.storage.saf

import com.stevesoltys.seedvault.service.storage.StoragePlugin
import com.stevesoltys.seedvault.service.storage.saf.legacy.DocumentsProviderLegacyPlugin
import com.stevesoltys.seedvault.service.storage.saf.legacy.LegacyStoragePlugin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val documentsProviderModule = module {
    single { DocumentsStorage(androidContext(), get()) }

    single<StoragePlugin> { DocumentsProviderStoragePlugin(androidContext(), get()) }
    @Suppress("Deprecation")
    single<LegacyStoragePlugin> { DocumentsProviderLegacyPlugin(androidContext(), get()) }
}
