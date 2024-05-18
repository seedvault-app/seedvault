/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.getRelativeTimeSpanString
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.RestoreSetAdapter.RestoreSetViewHolder

internal class RestoreSetAdapter(
    private val listener: RestorableBackupClickListener,
    private val items: List<RestorableBackup>,
) : Adapter<RestoreSetViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RestoreSetViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_restore_set, parent, false) as View
        return RestoreSetViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RestoreSetViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class RestoreSetViewHolder(private val v: View) : ViewHolder(v) {

        private val titleView = v.requireViewById<TextView>(R.id.titleView)
        private val subtitleView = v.requireViewById<TextView>(R.id.subtitleView)
        private val sizeView = v.requireViewById<TextView>(R.id.sizeView)

        internal fun bind(item: RestorableBackup) {
            v.setOnClickListener { listener.onRestorableBackupClicked(item) }
            titleView.text = item.name

            val lastBackup = getRelativeTime(item.time)
            val setup = getRelativeTime(item.token)
            subtitleView.text =
                v.context.getString(R.string.restore_restore_set_times, lastBackup, setup)
            val size = item.size
            if (size == null) {
                sizeView.visibility = GONE
            } else {
                sizeView.text = v.context.getString(
                    R.string.restore_restore_set_size,
                    Formatter.formatShortFileSize(v.context, size),
                )
                sizeView.visibility = VISIBLE
            }
        }

        private fun getRelativeTime(time: Long): CharSequence {
            val now = System.currentTimeMillis()
            return getRelativeTimeSpanString(time, now, HOUR_IN_MILLIS, FORMAT_ABBREV_RELATIVE)
        }

    }

}
