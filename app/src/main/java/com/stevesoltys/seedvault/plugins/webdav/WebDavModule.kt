package com.stevesoltys.seedvault.plugins.webdav

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storagePluginModuleWebDav = module {
    single { WebDavFactory(androidContext(), get()) }
    single { WebDavHandler(androidContext(), get(), get(), get()) }
}
