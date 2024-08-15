/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.restore

import android.content.res.Resources
import android.text.format.DateUtils.FORMAT_ABBREV_ALL
import android.text.format.DateUtils.getRelativeTimeSpanString
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.backup.BackupMediaFile.MediaType.AUDIO
import org.calyxos.backup.storage.backup.BackupMediaFile.MediaType.IMAGES
import org.calyxos.backup.storage.backup.BackupMediaFile.MediaType.VIDEO
import org.calyxos.backup.storage.ui.restore.FilesAdapter.FilesViewHolder

private class FilesItemCallback : DiffUtil.ItemCallback<FilesItem>() {
    override fun areItemsTheSame(oldItem: FilesItem, newItem: FilesItem): Boolean {
        if (oldItem is FileItem && newItem is FileItem) return newItem.file == oldItem.file
        if (oldItem is FolderItem && newItem is FolderItem) return newItem.name == oldItem.name
        return false
    }

    override fun areContentsTheSame(oldItem: FilesItem, newItem: FilesItem): Boolean {
        if (oldItem is FileItem && newItem is FileItem) return newItem.selected == oldItem.selected
        if (oldItem is FolderItem && newItem is FolderItem) {
            return newItem.selected == oldItem.selected && newItem.expanded == oldItem.expanded &&
                newItem.partiallySelected == oldItem.partiallySelected
        }
        return false
    }
}

internal class FilesAdapter(
    private val onExpandClicked: (FolderItem) -> Unit,
    private val onCheckedChanged: (FilesItem) -> Unit,
) : ListAdapter<FilesItem, FilesViewHolder>(FilesItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilesViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FilesViewHolder(v)
    }

    override fun onBindViewHolder(holder: FilesViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FilesViewHolder(itemView: View) : ViewHolder(itemView) {

        private val context = itemView.context
        private val expandView: ImageView = itemView.findViewById(R.id.expandView)
        private val nameView: TextView = itemView.findViewById(R.id.nameView)
        private val infoView: TextView = itemView.findViewById(R.id.infoView)
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)

        private val indentPadding = (8 * Resources.getSystem().displayMetrics.density).toInt()
        private val checkBoxDrawable = checkBox.buttonDrawable
        private val indeterminateDrawable =
            getDrawable(context, R.drawable.ic_indeterminate_check_box)

        fun bind(item: FilesItem) {
            if (item is FolderItem) {
                expandView.visibility = VISIBLE
                val res = if (item.expanded) {
                    R.drawable.ic_keyboard_arrow_down
                } else {
                    R.drawable.ic_chevron_right
                }
                expandView.setImageResource(res)
            } else if (item is FileItem) {
                expandView.setImageResource(getDrawableResource(item))
            }
            itemView.setOnClickListener {
                if (item is FolderItem) onExpandClicked(item)
                else checkBox.toggle()
            }
            itemView.updatePadding(left = indentPadding * item.level)
            nameView.text = item.name

            val now = System.currentTimeMillis()
            var text = Formatter.formatShortFileSize(context, item.size)
            item.lastModified?.let {
                text += " - " + getRelativeTimeSpanString(it, now, 0L, FORMAT_ABBREV_ALL)
            }
            if (item is FolderItem) {
                val numStr = context.getString(R.string.select_files_number_of_files, item.numFiles)
                text += " - $numStr"
            }
            infoView.text = text
            // unset and re-reset onCheckedChangeListener while updating checked state
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.selected
            checkBox.setOnCheckedChangeListener { _, _ ->
                onCheckedChanged(item)
            }
            if (item is FolderItem && item.partiallySelected) {
                checkBox.buttonDrawable = indeterminateDrawable
            } else {
                checkBox.buttonDrawable = checkBoxDrawable
            }
        }
    }

    private fun getDrawableResource(item: FileItem): Int = item.file.mediaFile?.type?.let { type ->
        when (type) {
            IMAGES -> R.drawable.ic_image
            VIDEO -> R.drawable.ic_video_file
            AUDIO -> R.drawable.ic_audio_file
            else -> R.drawable.ic_insert_drive_file
        }
    } ?: R.drawable.ic_insert_drive_file

}
