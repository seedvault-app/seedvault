package com.stevesoltys.seedvault.transport.restore

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val restoreModule = module {
    single { OutputFactory() }
    factory { ApkRestore(androidContext(), get()) }
    single { KVRestore(get<RestorePlugin>().kvRestorePlugin, get(), get(), get()) }
    single { FullRestore(get<RestorePlugin>().fullRestorePlugin, get(), get(), get()) }
    single { RestoreCoordinator(get(), get(), get(), get(), get()) }
}
