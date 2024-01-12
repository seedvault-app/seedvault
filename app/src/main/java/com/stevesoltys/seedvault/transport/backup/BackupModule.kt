package com.stevesoltys.seedvault.transport.backup

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single { InputFactory() }
    single {
        PackageService(
            context = androidContext(),
            backupManager = get(),
            settingsManager = get(),
            plugin = get()
        )
    }
    single {
        ApkBackup(
            pm = androidContext().packageManager,
            crypto = get(),
            settingsManager = get(),
            metadataManager = get()
        )
    }
    single<KvDbManager> { KvDbManagerImpl(androidContext()) }
    single {
        KVBackup(
            plugin = get(),
            settingsManager = get(),
            inputFactory = get(),
            crypto = get(),
            dbManager = get()
        )
    }
    single {
        FullBackup(
            plugin = get(),
            settingsManager = get(),
            inputFactory = get(),
            crypto = get()
        )
    }
    single {
        BackupCoordinator(
            context = androidContext(),
            plugin = get(),
            kv = get(),
            full = get(),
            apkBackup = get(),
            clock = get(),
            packageService = get(),
            metadataManager = get(),
            settingsManager = get(),
            nm = get()
        )
    }
}
