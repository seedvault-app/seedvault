package com.stevesoltys.seedvault.transport.backup

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single { InputFactory() }
    single { KVBackup(get<BackupPlugin>().kvBackupPlugin, get(), get(), get()) }
    single { FullBackup(get<BackupPlugin>().fullBackupPlugin, get(), get(), get()) }
    single { BackupCoordinator(androidContext(), get(), get(), get(), get(), get(), get()) }
}
