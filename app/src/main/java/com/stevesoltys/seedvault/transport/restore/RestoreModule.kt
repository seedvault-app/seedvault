package com.stevesoltys.seedvault.transport.restore

import org.koin.dsl.module

val restoreModule = module {
    single { OutputFactory() }
    single { KVRestore(get<RestorePlugin>().kvRestorePlugin, get(), get(), get()) }
    single { FullRestore(get<RestorePlugin>().fullRestorePlugin, get(), get(), get()) }
    single { RestoreCoordinator(get(), get(), get(), get(), get()) }
}
