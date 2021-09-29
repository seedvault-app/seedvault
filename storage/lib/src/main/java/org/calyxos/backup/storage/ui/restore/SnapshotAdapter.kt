package org.calyxos.backup.storage.ui.restore

import android.text.format.DateUtils.FORMAT_ABBREV_ALL
import android.text.format.DateUtils.getRelativeTimeSpanString
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
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
        private val timeView: TextView = view.findViewById(R.id.timeView)
        private val sizeView: TextView = view.findViewById(R.id.sizeView)

        fun bind(item: SnapshotItem) {
            if (item.snapshot == null) {
                // TODO also remove clickable background
                layout.setOnClickListener(null)
            } else {
                layout.setOnClickListener { listener.onSnapshotClicked(item) }
            }
            nameView.text = item.snapshot?.name
            val now = System.currentTimeMillis()
            timeView.text = getRelativeTimeSpanString(item.time, now, 0L, FORMAT_ABBREV_ALL)
            sizeView.text = item.snapshot?.size?.let { size ->
                Formatter.formatShortFileSize(sizeView.context, size)
            }
        }
    }

}
