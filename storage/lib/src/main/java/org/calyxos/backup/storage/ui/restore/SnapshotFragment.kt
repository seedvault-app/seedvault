/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.RecyclerView
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.api.SnapshotResult

public interface SnapshotViewModel {
    public val snapshots: LiveData<SnapshotResult>
    public val fileSelectionManager: FileSelectionManager
}

internal interface SnapshotClickListener {
    fun onSnapshotClicked(item: SnapshotItem)
}

public abstract class SnapshotFragment : Fragment(), SnapshotClickListener {

    protected abstract val viewModel: SnapshotViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        requireActivity().setTitle(R.string.snapshots_title)

        val v = inflater.inflate(R.layout.fragment_snapshot, container, false)
        val list: RecyclerView = v.findViewById(R.id.list)
        val progressBar: ProgressBar = v.findViewById(R.id.progressBar)
        val emptyStateView: TextView = v.findViewById(R.id.emptyStateView)

        val adapter = SnapshotAdapter(this)
        list.adapter = adapter
        viewModel.snapshots.observe(viewLifecycleOwner) {
            progressBar.visibility = INVISIBLE
            when (it) {
                is SnapshotResult.Success -> {
                    if (it.snapshots.isEmpty()) {
                        emptyStateView.visibility = VISIBLE
                    } else adapter.submitList(it.snapshots)
                }

                is SnapshotResult.Error -> {
                    val color = resources.getColor(R.color.design_default_color_error, null)
                    emptyStateView.setTextColor(color)
                    emptyStateView.setText(R.string.snapshots_error)
                    emptyStateView.visibility = VISIBLE
                }
            }
        }
        return v
    }

}
