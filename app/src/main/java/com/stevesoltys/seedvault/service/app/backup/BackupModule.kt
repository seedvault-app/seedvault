package com.stevesoltys.seedvault.service.app.backup

import com.stevesoltys.seedvault.service.app.PackageService
import com.stevesoltys.seedvault.service.app.backup.apk.ApkBackupService
import com.stevesoltys.seedvault.service.app.backup.coordinator.BackupCoordinatorService
import com.stevesoltys.seedvault.service.app.backup.full.FullBackupService
import com.stevesoltys.seedvault.service.app.backup.kv.KVBackupService
import com.stevesoltys.seedvault.service.app.backup.kv.KvDbManager
import com.stevesoltys.seedvault.service.app.backup.kv.KvDbManagerImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single { InputFactory() }
    single {
        PackageService(
            context = androidContext(),
            settingsService = get(),
            plugin = get()
        )
    }
    single {
        ApkBackupService(
            pm = androidContext().packageManager,
            cryptoService = get(),
            settingsService = get(),
            metadataService = get()
        )
    }
    single<KvDbManager> { KvDbManagerImpl(androidContext()) }
    single {
        KVBackupService(
            plugin = get(),
            settingsService = get(),
            inputFactory = get(),
            cryptoService = get(),
            dbManager = get()
        )
    }
    single {
        FullBackupService(
            plugin = get(),
            settingsService = get(),
            inputFactory = get(),
            cryptoService = get()
        )
    }
    single {
        BackupCoordinatorService(
            context = androidContext(),
            plugin = get(),
            kv = get(),
            full = get(),
            apkBackupService = get(),
            timeSource = get(),
            packageService = get(),
            metadataService = get(),
            settingsService = get(),
            nm = get()
        )
    }
}
