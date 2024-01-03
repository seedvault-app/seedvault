package com.stevesoltys.seedvault.service.app.restore

import com.stevesoltys.seedvault.service.app.restore.coordinator.RestoreCoordinator
import com.stevesoltys.seedvault.service.app.restore.full.FullRestore
import com.stevesoltys.seedvault.service.app.restore.kv.KVRestore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val restoreModule = module {
    single { OutputFactory() }
    single { KVRestore(get(), get(), get(), get(), get(), get()) }
    single { FullRestore(get(), get(), get(), get(), get()) }
    single {
        RestoreCoordinator(androidContext(), get(), get(), get(), get(), get(), get(), get(), get())
    }
}
