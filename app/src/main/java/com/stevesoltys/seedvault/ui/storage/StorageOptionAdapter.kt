/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.storage

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.storage.StorageOption.SafOption
import com.stevesoltys.seedvault.ui.storage.StorageOptionAdapter.StorageOptionViewHolder

internal class StorageOptionAdapter(
    private val isRestore: Boolean,
    private val listener: StorageOptionClickedListener,
) : Adapter<StorageOptionViewHolder>() {

    private val items = ArrayList<StorageOption>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StorageOptionViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_storage_root, parent, false) as View
        return StorageOptionViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: StorageOptionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun setItems(items: List<StorageOption>) {
        this.items.clear()
        this.items.addAll(items)
        notifyDataSetChanged()
    }

    internal inner class StorageOptionViewHolder(private val v: View) : ViewHolder(v) {

        private val iconView = v.requireViewById<ImageView>(R.id.iconView)
        private val titleView = v.requireViewById<TextView>(R.id.titleView)
        private val summaryView = v.requireViewById<TextView>(R.id.summaryView)

        internal fun bind(item: StorageOption) {
            if (item.enabled) {
                v.isEnabled = true
                v.alpha = 1f
            } else {
                v.isEnabled = false
                v.alpha = 0.3f
            }

            iconView.setImageDrawable(item.icon)
            titleView.text = item.title
            when {
                item.summary != null -> {
                    summaryView.text = item.summary
                    summaryView.visibility = VISIBLE
                }
                item.availableBytes != null -> {
                    val str = Formatter.formatFileSize(v.context, item.availableBytes!!)
                    summaryView.text = v.context.getString(R.string.storage_available_bytes, str)
                    summaryView.visibility = VISIBLE
                }
                else -> summaryView.visibility = GONE
            }
            v.setOnClickListener {
                if (item.nonDefaultAction != null) {
                    item.nonDefaultAction?.invoke()
                } else if (!isRestore && item is SafOption && item.isInternal()) {
                    showWarningDialog(v.context, item)
                } else {
                    listener.onClick(item)
                }
            }
        }

    }

    private fun showWarningDialog(context: Context, item: StorageOption) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.storage_internal_warning_title)
            .setMessage(R.string.storage_internal_warning_message)
            .setPositiveButton(R.string.storage_internal_warning_choose_other) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(R.string.storage_internal_warning_use_anyway) { dialog, _ ->
                dialog.dismiss()
                listener.onClick(item)
            }
            .show()
    }

}
