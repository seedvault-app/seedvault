package com.stevesoltys.seedvault.plugins.webdav

import com.stevesoltys.seedvault.plugins.StoragePlugin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val webDavModule = module {
    // TODO PluginManager should create the plugin on demand
    single<StoragePlugin<*>> { WebDavStoragePlugin(androidContext(), WebDavConfig("", "", "")) }
}
