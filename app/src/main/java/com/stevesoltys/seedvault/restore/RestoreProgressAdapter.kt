/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.RecyclerView.Adapter
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.RestoreProgressAdapter.PackageViewHolder
import com.stevesoltys.seedvault.ui.AppViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.LinkedList

internal class RestoreProgressAdapter(
    val scope: CoroutineScope,
    val iconLoader: suspend (AppRestoreResult, (Drawable) -> Unit) -> Unit,
) : Adapter<PackageViewHolder>() {

    private val diffCallback = object : ItemCallback<AppRestoreResult>() {
        override fun areItemsTheSame(
            oldItem: AppRestoreResult,
            newItem: AppRestoreResult,
        ): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(old: AppRestoreResult, new: AppRestoreResult): Boolean {
            return old.name == new.name && old.state == new.state
        }
    }
    private val differ = AsyncListDiffer(this, diffCallback)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_app_status, parent, false)
        return PackageViewHolder(v)
    }

    override fun getItemCount() = differ.currentList.size

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
    }

    fun update(newItems: LinkedList<AppRestoreResult>, callback: Runnable) {
        // add .toList(), because [AppDataRestoreManager] still re-uses the same list,
        // but AsyncListDiffer needs a new one.
        differ.submitList(newItems.toList(), callback)
    }

    override fun onViewRecycled(holder: PackageViewHolder) {
        holder.iconJob?.cancel()
    }

    inner class PackageViewHolder(v: View) : AppViewHolder(v) {
        var iconJob: Job? = null
        fun bind(item: AppRestoreResult) {
            appName.text = item.name
            if (item.packageName == MAGIC_PACKAGE_MANAGER) {
                appIcon.setImageResource(R.drawable.ic_launcher_default)
            } else {
                try {
                    appIcon.setImageDrawable(pm.getApplicationIcon(item.packageName))
                } catch (e: NameNotFoundException) {
                    iconJob = scope.launch {
                        iconLoader(item) { bitmap ->
                            appIcon.setImageDrawable(bitmap)
                        }
                    }
                }
            }
            setState(item.state, true)
        }
    }

}
