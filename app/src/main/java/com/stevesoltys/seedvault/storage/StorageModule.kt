package com.stevesoltys.seedvault.storage

import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.api.StoragePlugin
import org.koin.dsl.module

val storageModule = module {
    single<StoragePlugin> { SeedvaultSafStoragePlugin(get(), get(), get()) }
    single { StorageBackup(get(), get()) }
}
