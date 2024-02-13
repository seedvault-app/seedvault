package com.stevesoltys.seedvault.plugins.webdav

import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsProviderLegacyPlugin
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val webDavModule = module {
    // TODO PluginManager should create the plugin on demand
    single<StoragePlugin> { WebDavStoragePlugin(androidContext(), WebDavConfig("", "", "")) }

    single { DocumentsStorage(androidContext(), get()) }
    @Suppress("Deprecation")
    single<LegacyStoragePlugin> { DocumentsProviderLegacyPlugin(androidContext(), get()) }
}
