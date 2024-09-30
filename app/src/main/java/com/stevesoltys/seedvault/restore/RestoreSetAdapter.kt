/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.getRelativeTimeSpanString
import android.text.format.Formatter.formatShortFileSize
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
import com.stevesoltys.seedvault.transport.restore.RestorableBackup

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
        private val appView = v.requireViewById<TextView>(R.id.appView)
        private val apkView = v.requireViewById<TextView>(R.id.apkView)
        private val timeView = v.requireViewById<TextView>(R.id.timeView)

        internal fun bind(item: RestorableBackup) {
            v.setOnClickListener { listener.onRestorableBackupClicked(item) }
            titleView.text = item.name

            appView.text = if (item.sizeAppData > 0) {
                v.context.getString(
                    R.string.restore_restore_set_apps,
                    item.numAppData,
                    formatShortFileSize(v.context, item.sizeAppData),
                )
            } else {
                v.context.getString(R.string.restore_restore_set_apps_no_size, item.numAppData)
            }
            appView.visibility = if (item.numAppData > 0) VISIBLE else GONE
            apkView.text = if (item.sizeApks > 0) {
                v.context.getString(
                    R.string.restore_restore_set_apks,
                    item.numApks,
                    formatShortFileSize(v.context, item.sizeApks),
                )
            } else {
                v.context.getString(R.string.restore_restore_set_apks_no_size, item.numApks)
            }
            apkView.visibility = if (item.numApks > 0) VISIBLE else GONE
            timeView.text = getRelativeTime(item.time)
        }

        private fun getRelativeTime(time: Long): CharSequence {
            val now = System.currentTimeMillis()
            return getRelativeTimeSpanString(time, now, MINUTE_IN_MILLIS, FORMAT_ABBREV_RELATIVE)
        }

    }

}
