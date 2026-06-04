/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.restore

import android.text.format.DateUtils.FORMAT_ABBREV_ALL
import android.text.format.DateUtils.getRelativeTimeSpanString
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.ui.restore.SnapshotAdapter.SnapshotViewHolder

private class SnapshotItemCallback : DiffUtil.ItemCallback<SnapshotItem>() {
    override fun areItemsTheSame(oldItem: SnapshotItem, newItem: SnapshotItem): Boolean {
        return newItem.time == oldItem.time
    }

    override fun areContentsTheSame(oldItem: SnapshotItem, newItem: SnapshotItem): Boolean {
        val newSnapshot = newItem.snapshot
        val oldSnapshot = oldItem.snapshot
        return newSnapshot?.name == oldSnapshot?.name &&
            newSnapshot?.size == oldSnapshot?.size
    }
}

internal class SnapshotAdapter(private val listener: SnapshotClickListener) :
    ListAdapter<SnapshotItem, SnapshotViewHolder>(SnapshotItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnapshotViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_snapshot, parent, false)
        return SnapshotViewHolder(v)
    }

    override fun onBindViewHolder(holder: SnapshotViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SnapshotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: ViewGroup = view.findViewById(R.id.layout)
        private val nameView: TextView = view.findViewById(R.id.nameView)
        private val infoView: TextView = view.findViewById(R.id.infoView)
        private val errorView: TextView = view.findViewById(R.id.errorView)
        private val timeView: TextView = view.findViewById(R.id.timeView)

        fun bind(item: SnapshotItem) {
            val s = item.snapshot
            if (s == null) {
                // TODO also remove clickable background
                layout.setOnClickListener(null)
            } else {
                layout.setOnClickListener { listener.onSnapshotClicked(item) }
            }
            nameView.text = s?.name
            val nFiles = (s?.mediaFilesCount ?: 0) + (s?.documentFilesCount ?: 0)
            val infoStr = infoView.context.getString(R.string.select_files_number_of_files, nFiles)
            val sizeStr = s?.size?.let { size ->
                Formatter.formatShortFileSize(infoView.context, size)
            }
            infoView.text = if (sizeStr == null) infoStr else "$infoStr ($sizeStr)"
            if (item.hasError) {
                errorView.visibility = VISIBLE
            } else {
                errorView.visibility = GONE
            }
            val now = System.currentTimeMillis()
            timeView.text = getRelativeTimeSpanString(item.time, now, 0L, FORMAT_ABBREV_ALL)
        }
    }
}
