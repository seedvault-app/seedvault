/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView.Adapter
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.ui.AppViewHolder
import com.stevesoltys.seedvault.ui.notification.getAppName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal interface InstallItemListener {
    fun onFailedItemClicked(item: ApkInstallResult)
}

internal class InstallProgressAdapter(
    private val scope: CoroutineScope,
    private val iconLoader: suspend (ApkInstallResult, (Drawable) -> Unit) -> Unit,
    private val listener: InstallItemListener,
) : Adapter<InstallProgressAdapter.AppInstallViewHolder>() {

    private var finished = false

    private val diffCallback = object : DiffUtil.ItemCallback<ApkInstallResult>() {
        override fun areItemsTheSame(item1: ApkInstallResult, item2: ApkInstallResult): Boolean =
            item1.packageName == item2.packageName

        override fun areContentsTheSame(old: ApkInstallResult, new: ApkInstallResult): Boolean {
            // update failed items when finished
            return if (finished) new.state != FAILED && old == new
            else old == new
        }
    }
    private val differ = AsyncListDiffer(this, diffCallback)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppInstallViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_app_status, parent, false)
        return AppInstallViewHolder(v)
    }

    override fun getItemCount() = differ.currentList.size

    override fun onBindViewHolder(holder: AppInstallViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
    }

    fun update(items: List<ApkInstallResult>, block: Runnable) {
        differ.submitList(items, block)
    }

    fun setFinished() {
        finished = true
    }

    override fun onViewRecycled(holder: AppInstallViewHolder) {
        holder.iconJob?.cancel()
    }

    internal inner class AppInstallViewHolder(v: View) : AppViewHolder(v) {
        var iconJob: Job? = null
        fun bind(item: ApkInstallResult) {
            v.setOnClickListener(null)
            v.background = null

            if (item.icon == null) iconJob = scope.launch {
                iconLoader(item, appIcon::setImageDrawable)
            } else appIcon.setImageDrawable(item.icon)
            appName.text = item.name ?: getAppName(v.context, item.packageName)
            appInfo.visibility = GONE
            when (item.state) {
                IN_PROGRESS -> {
                    appStatus.visibility = INVISIBLE
                    progressBar.visibility = VISIBLE
                    progressBar.stateDescription =
                        context.getString(R.string.restore_app_status_installing)
                }
                SUCCEEDED -> {
                    appStatus.setImageResource(R.drawable.ic_check_green)
                    appStatus.visibility = VISIBLE
                    appStatus.contentDescription =
                        context.getString(R.string.restore_app_status_installed)
                    progressBar.visibility = INVISIBLE
                }
                FAILED -> {
                    appStatus.setImageResource(R.drawable.ic_error_red)
                    appStatus.visibility = VISIBLE
                    appStatus.contentDescription =
                        context.getString(R.string.restore_app_status_install_error)
                    progressBar.visibility = INVISIBLE
                    if (finished) {
                        v.background = clickableBackground
                        v.setOnClickListener {
                            listener.onFailedItemClicked(item)
                        }
                        appInfo.visibility = VISIBLE
                        appInfo.setText(R.string.restore_installing_tap_to_install)
                    }
                }
                FAILED_SYSTEM_APP -> {
                    appStatus.setImageResource(R.drawable.ic_error_red)
                    appStatus.contentDescription =
                        context.getString(R.string.restore_app_status_install_error)
                    appStatus.visibility = VISIBLE
                    progressBar.visibility = INVISIBLE
                }
                QUEUED -> throw AssertionError()
            }
        }
    } // end AppInstallViewHolder

}
