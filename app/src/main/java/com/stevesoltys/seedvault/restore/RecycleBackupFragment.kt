/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.stevesoltys.seedvault.R
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class RecycleBackupFragment : Fragment() {

    private val viewModel: RestoreViewModel by sharedViewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_recycle_backup, container, false)

        val backupName = viewModel.chosenRestorableBackup.value?.name
        v.requireViewById<TextView>(R.id.descriptionView).text =
            getString(R.string.restore_recycle_backup_text, backupName)

        v.requireViewById<Button>(R.id.noButton).setOnClickListener {
            viewModel.onRecycleBackupFinished(false)
        }
        v.requireViewById<Button>(R.id.yesButton).setOnClickListener {
            viewModel.onRecycleBackupFinished(true)
        }
        return v
    }

}
