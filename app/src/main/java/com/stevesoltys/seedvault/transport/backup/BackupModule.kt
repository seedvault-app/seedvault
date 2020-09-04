package com.stevesoltys.seedvault.transport.backup

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single { InputFactory() }
    single { PackageService(androidContext(), get()) }
    single { ApkBackup(androidContext().packageManager, get(), get()) }
    single { KVBackup(get<BackupPlugin>().kvBackupPlugin, get(), get(), get(), get()) }
    single { FullBackup(get<BackupPlugin>().fullBackupPlugin, get(), get(), get()) }
    single { BackupCoordinator(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
