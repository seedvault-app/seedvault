/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import androidx.fragment.app.activityViewModels
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import de.grobox.storagebackuptester.MainViewModel
import de.grobox.storagebackuptester.R
import org.calyxos.backup.storage.ui.restore.FileSelectionFragment
import org.calyxos.backup.storage.ui.restore.FilesItem

class DemoFileSelectionFragment : FileSelectionFragment() {

    override val viewModel: MainViewModel by activityViewModels()
    private var fab: ExtendedFloatingActionButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        val topStub: ViewStub = v.findViewById(R.id.topStub)
        topStub.layoutResource = R.layout.header_file_select
        topStub.inflate()

        val bottomStub: ViewStub = v.findViewById(R.id.bottomStub)
        bottomStub.layoutResource = R.layout.footer_files
        val footer = bottomStub.inflate() as ExtendedFloatingActionButton
        fab = footer
        footer.setOnClickListener {
            viewModel.onFilesSelected()
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, RestoreFragment.newInstance())
                .commit()
        }
        return v
    }

    override fun onFileItemsChanged(filesItems: List<FilesItem>) {
        super.onFileItemsChanged(filesItems)
        slideUpInRootView(fab!!)
    }
}
