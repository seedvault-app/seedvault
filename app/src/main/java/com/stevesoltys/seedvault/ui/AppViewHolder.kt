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
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED
import com.stevesoltys.seedvault.ui.AppBackupState.IN_PROGRESS
import com.stevesoltys.seedvault.ui.AppBackupState.SUCCEEDED

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

    protected fun setState(state: AppBackupState, isRestore: Boolean) {
        if (state == IN_PROGRESS) {
            appInfo.visibility = GONE
            appStatus.visibility = INVISIBLE
            progressBar.visibility = VISIBLE
        } else {
            appStatus.visibility = VISIBLE
            progressBar.visibility = INVISIBLE
            appInfo.visibility = GONE
            when (state) {
                SUCCEEDED -> appStatus.setImageResource(R.drawable.ic_check_green)
                FAILED -> appStatus.setImageResource(R.drawable.ic_error_red)
                else -> {
                    appStatus.setImageResource(R.drawable.ic_warning_yellow)
                    appInfo.text =
                        if (isRestore) state.getRestoreText(context)
                        else state.getBackupText(context)
                    appInfo.visibility = VISIBLE
                }
            }
        }
    }

}
