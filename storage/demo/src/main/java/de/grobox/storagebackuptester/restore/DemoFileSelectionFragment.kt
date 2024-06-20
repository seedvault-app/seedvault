/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import de.grobox.storagebackuptester.MainViewModel
import org.calyxos.backup.storage.ui.restore.FileSelectionFragment

class DemoFileSelectionFragment : FileSelectionFragment() {

    override val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        // val topStub: ViewStub = v.findViewById(R.id.topStub)
        // topStub.layoutResource = R.layout.footer_snapshot
        // val header = topStub.inflate()
        // header.findViewById<Button>(R.id.button).setOnClickListener {
        //     requireActivity().onBackPressed()
        // }
        return v
    }


}
