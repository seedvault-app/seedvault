/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.files

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.settings.SettingsViewModel
import org.calyxos.backup.storage.ui.backup.BackupContentFragment
import org.koin.androidx.viewmodel.ext.android.sharedViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class FileSelectionFragment : BackupContentFragment() {

    override val viewModel by viewModel<FileSelectionViewModel>()
    private val settingsViewModel by sharedViewModel<SettingsViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        requireActivity().setTitle(R.string.settings_backup_files_title)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        // reload files summary when we navigate away (it might have changed)
        settingsViewModel.loadFilesSummary()
    }
}
