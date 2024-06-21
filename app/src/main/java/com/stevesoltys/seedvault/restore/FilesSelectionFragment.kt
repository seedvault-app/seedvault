/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import com.stevesoltys.seedvault.R
import org.calyxos.backup.storage.ui.restore.FileSelectionFragment
import org.calyxos.backup.storage.ui.restore.FilesItem
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

internal class FilesSelectionFragment : FileSelectionFragment() {

    override val viewModel: RestoreViewModel by sharedViewModel()
    private lateinit var button: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        val topStub: ViewStub = v.requireViewById(R.id.topStub)
        topStub.layoutResource = R.layout.header_files_selection
        topStub.inflate()
        val bottomStub: ViewStub = v.requireViewById(R.id.bottomStub)
        bottomStub.layoutResource = R.layout.footer_files_selection
        button = bottomStub.inflate() as Button
        button.setOnClickListener {
            viewModel.startFilesRestore()
        }
        return v
    }

    override fun onFileItemsChanged(filesItems: List<FilesItem>) {
        slideUpInRootView(button)
    }
}
