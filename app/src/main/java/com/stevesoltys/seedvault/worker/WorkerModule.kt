/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import com.stevesoltys.seedvault.transport.backup.AppBackupManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val workerModule = module {
    factory {
        BackupRequester(
            context = androidContext(),
            backupManager = get(),
            packageService = get(),
        )
    }
    factory {
        IconManager(
            context = androidContext(),
            packageService = get(),
            crypto = get(),
            backupReceiver = get(),
            loader = get(),
            appBackupManager = get(),
        )
    }
    single { AppBackupManager(get(), get(), get(), get()) }
    single {
        ApkBackup(
            pm = androidContext().packageManager,
            backupReceiver = get(),
            appBackupManager = get(),
            snapshotManager = get(),
            settingsManager = get(),
        )
    }
    single {
        ApkBackupManager(
            context = androidContext(),
            settingsManager = get(),
            metadataManager = get(),
            packageService = get(),
            apkBackup = get(),
            iconManager = get(),
            nm = get()
        )
    }
}
