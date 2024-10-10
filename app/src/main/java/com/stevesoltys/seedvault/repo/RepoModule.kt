/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val repoModule = module {
    single { AppBackupManager(get(), get(), get(), get(), get(), get()) }
    single { BackupReceiver(get(), get(), get()) }
    single { BlobCache(androidContext()) }
    single { BlobCreator(get(), get()) }
    single { Loader(get(), get()) }
    single {
        val snapshotFolder = File(androidContext().filesDir, FOLDER_SNAPSHOTS)
        SnapshotManager(snapshotFolder, get(), get(), get())
    }
    factory { SnapshotCreatorFactory(androidContext(), get(), get(), get()) }
    factory { Pruner(get(), get(), get()) }
}
