package com.stevesoltys.seedvault.ui

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.AppRestoreStatus
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NOT_INSTALLED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NO_DATA
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_WAS_STOPPED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.IN_PROGRESS
import com.stevesoltys.seedvault.restore.AppRestoreStatus.NOT_YET_BACKED_UP
import com.stevesoltys.seedvault.restore.AppRestoreStatus.SUCCEEDED

internal abstract class AppViewHolder(protected val v: View) : RecyclerView.ViewHolder(v) {

    protected val context: Context = v.context
    protected val pm: PackageManager = context.packageManager

    protected val clickableBackground = v.background!!
    protected val appIcon: ImageView = v.findViewById(R.id.appIcon)
    protected val appName: TextView = v.findViewById(R.id.appName)
    protected val appInfo: TextView = v.findViewById(R.id.appInfo)
    protected val appStatus: ImageView = v.findViewById(R.id.appStatus)
    protected val progressBar: ProgressBar = v.findViewById(R.id.progressBar)
    protected val switchView: Switch = v.findViewById(R.id.switchView)

    init {
        // don't use clickable background by default
        v.background = null
    }

    protected fun setStatus(status: AppRestoreStatus) {
        if (status == IN_PROGRESS) {
            appInfo.visibility = GONE
            appStatus.visibility = INVISIBLE
            progressBar.visibility = VISIBLE
        } else {
            appStatus.visibility = VISIBLE
            progressBar.visibility = INVISIBLE
            appInfo.visibility = GONE
            when (status) {
                SUCCEEDED -> appStatus.setImageResource(R.drawable.ic_check_green)
                FAILED -> appStatus.setImageResource(R.drawable.ic_error_red)
                else -> {
                    appStatus.setImageResource(R.drawable.ic_warning_yellow)
                    appInfo.text = status.getInfo()
                    appInfo.visibility = VISIBLE
                }
            }
        }
    }

    private fun AppRestoreStatus.getInfo(): String = when (this) {
        NOT_YET_BACKED_UP -> context.getString(R.string.restore_app_not_yet_backed_up)
        FAILED_NO_DATA -> context.getString(R.string.restore_app_no_data)
        FAILED_WAS_STOPPED -> context.getString(R.string.restore_app_was_stopped)
        FAILED_NOT_ALLOWED -> context.getString(R.string.restore_app_not_allowed)
        FAILED_NOT_INSTALLED -> context.getString(R.string.restore_app_not_installed)
        FAILED_QUOTA_EXCEEDED -> context.getString(R.string.restore_app_quota_exceeded)
        else -> "Please report a bug after you read this."
    }

}
