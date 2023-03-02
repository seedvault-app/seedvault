/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single { BackupInitializer(get()) }
    single { InputFactory() }
    single {
        PackageService(
            context = androidContext(),
            backupManager = get(),
            settingsManager = get(),
            pluginManager = get(),
        )
    }
    single<KvDbManager> { KvDbManagerImpl(androidContext()) }
    single {
        KVBackup(
            pluginManager = get(),
            settingsManager = get(),
            nm = get(),
            inputFactory = get(),
            crypto = get(),
            dbManager = get(),
        )
    }
    single {
        FullBackup(
            pluginManager = get(),
            settingsManager = get(),
            nm = get(),
            inputFactory = get(),
            crypto = get(),
        )
    }
    single {
        BackupCoordinator(
            context = androidContext(),
            pluginManager = get(),
            kv = get(),
            full = get(),
            clock = get(),
            packageService = get(),
            metadataManager = get(),
            settingsManager = get(),
            nm = get(),
        )
    }
}
