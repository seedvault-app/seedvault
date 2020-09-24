package com.stevesoltys.seedvault.transport.backup

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single { InputFactory() }
    single {
        PackageService(
            context = androidContext(),
            backupManager = get()
        )
    }
    single {
        ApkBackup(
            pm = androidContext().packageManager,
            settingsManager = get(),
            metadataManager = get()
        )
    }
    single {
        KVBackup(
            plugin = get<BackupPlugin>().kvBackupPlugin,
            inputFactory = get(),
            headerWriter = get(),
            crypto = get(),
            nm = get()
        )
    }
    single {
        FullBackup(
            plugin = get<BackupPlugin>().fullBackupPlugin,
            inputFactory = get(),
            headerWriter = get(),
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
