/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED
import com.stevesoltys.seedvault.ui.AppBackupState.IN_PROGRESS
import com.stevesoltys.seedvault.ui.AppBackupState.SUCCEEDED

internal abstract class AppViewHolder(protected val v: View) : RecyclerView.ViewHolder(v) {

    protected val context: Context = v.context
    protected val pm: PackageManager = context.packageManager

    protected val clickableBackground = v.background!!
    protected val appIcon: ImageView = v.requireViewById(R.id.appIcon)
    protected val appName: TextView = v.requireViewById(R.id.appName)
    protected val appInfo: TextView = v.requireViewById(R.id.appInfo)
    protected val appStatus: ImageView = v.requireViewById(R.id.appStatus)
    protected val progressBar: ProgressBar = v.requireViewById(R.id.progressBar)
    protected val switchView: SwitchMaterial = v.requireViewById(R.id.switchView)

    init {
        // don't use clickable background by default
        v.background = null
    }

    protected fun setState(state: AppBackupState, isRestore: Boolean) {
        if (state == IN_PROGRESS) {
            appInfo.visibility = GONE
            appStatus.visibility = INVISIBLE
            progressBar.visibility = VISIBLE
            progressBar.stateDescription = context.getString(
                if (isRestore) R.string.restore_restoring
                else R.string.backup_app_in_progress
            )
        } else {
            appStatus.visibility = VISIBLE
            progressBar.visibility = INVISIBLE
            appInfo.visibility = GONE
            val contentDescription: String?
            when (state) {
                SUCCEEDED -> {
                    appStatus.setImageResource(R.drawable.ic_check_green)
                    contentDescription = context.getString(
                        if (isRestore) R.string.restore_app_status_restored
                        else R.string.backup_app_success
                    )
                }
                FAILED -> {
                    appStatus.setImageResource(R.drawable.ic_error_red)
                    contentDescription = context.getString(
                        if (isRestore) R.string.restore_app_status_failed
                        else R.string.notification_failed_title
                    )
                }
                else -> {
                    appStatus.setImageResource(R.drawable.ic_warning_yellow)
                    contentDescription = context.getString(
                        if (isRestore) R.string.restore_app_status_warning
                        else R.string.backup_app_warning
                    )
                    appInfo.text =
                        if (isRestore) state.getRestoreText(context)
                        else state.getBackupText(context)
                    appInfo.visibility = VISIBLE
                }
            }
            appStatus.contentDescription = contentDescription
        }
    }

}
