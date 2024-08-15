/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import com.stevesoltys.seedvault.ui.files.FileSelectionViewModel
import org.calyxos.backup.storage.ui.restore.FileSelectionManager
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val restoreUiModule = module {
    single { FileSelectionManager() }
    viewModel {
        RestoreViewModel(
            app = androidApplication(),
            settingsManager = get(),
            keyManager = get(),
            backupManager = get(),
            restoreCoordinator = get(),
            apkRestore = get(),
            iconManager = get(),
            storageBackup = get(),
            pluginManager = get(),
            fileSelectionManager = get(),
        )
    }
    viewModel { FileSelectionViewModel(androidApplication(), get()) }
}
