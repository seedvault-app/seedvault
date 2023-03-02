/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.app.Activity.RESULT_OK
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.stevesoltys.seedvault.R
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.ui.restore.SnapshotFragment
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

internal class RestoreFilesFragment : SnapshotFragment() {
    override val viewModel: RestoreViewModel by sharedViewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)

        val topStub: ViewStub = v.requireViewById(R.id.topStub)
        topStub.layoutResource = R.layout.header_snapshots
        topStub.inflate()

        val bottomStub: ViewStub = v.requireViewById(R.id.bottomStub)
        bottomStub.layoutResource = R.layout.footer_snapshots
        val footer = bottomStub.inflate()
        val skipView: TextView = footer.requireViewById(R.id.skipView)
        skipView.setOnClickListener {
            requireActivity().apply {
                setResult(RESULT_OK)
                finishAfterTransition()
            }
        }
        return v
    }

    override fun onSnapshotClicked(item: SnapshotItem) {
        viewModel.startFilesRestore(item)
    }
}

internal class RestoreFilesStartedFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_restore_files_started, container, false)

        val button: Button = v.requireViewById(R.id.button)
        button.setOnClickListener {
            requireActivity().apply {
                setResult(RESULT_OK)
                finishAfterTransition()
            }
        }
        return v
    }
}
