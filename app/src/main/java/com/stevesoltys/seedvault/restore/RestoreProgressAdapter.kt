package com.stevesoltys.seedvault.restore

import android.content.pm.PackageManager.NameNotFoundException
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NOT_INSTALLED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_NO_DATA
import com.stevesoltys.seedvault.restore.AppRestoreStatus.FAILED_QUOTA_EXCEEDED
import com.stevesoltys.seedvault.restore.AppRestoreStatus.IN_PROGRESS
import com.stevesoltys.seedvault.restore.AppRestoreStatus.SUCCEEDED
import com.stevesoltys.seedvault.restore.RestoreProgressAdapter.PackageViewHolder
import java.util.*

internal class RestoreProgressAdapter : Adapter<PackageViewHolder>() {

    private val items = LinkedList<AppRestoreResult>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.list_item_app_status, parent, false)
        return PackageViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun update(newItems: LinkedList<AppRestoreResult>) {
        val diffResult = DiffUtil.calculateDiff(Diff(items, newItems))
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    private class Diff(
            private val oldItems: LinkedList<AppRestoreResult>,
            private val newItems: LinkedList<AppRestoreResult>) : DiffUtil.Callback() {

        override fun getOldListSize() = oldItems.size
        override fun getNewListSize() = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition].packageName == newItems[newItemPosition].packageName
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition] == newItems[newItemPosition]
        }
    }

    inner class PackageViewHolder(v: View) : ViewHolder(v) {

        private val context = v.context
        private val pm = context.packageManager
        private val appIcon: ImageView = v.findViewById(R.id.appIcon)
        private val appName: TextView = v.findViewById(R.id.appName)
        private val appInfo: TextView = v.findViewById(R.id.appInfo)
        private val appStatus: ImageView = v.findViewById(R.id.appStatus)
        private val progressBar: ProgressBar = v.findViewById(R.id.progressBar)

        fun bind(item: AppRestoreResult) {
            appName.text = item.name
            if (item.packageName == MAGIC_PACKAGE_MANAGER) {
                appIcon.setImageResource(R.drawable.ic_launcher_default)
            } else {
                try {
                    appIcon.setImageDrawable(pm.getApplicationIcon(item.packageName))
                } catch (e: NameNotFoundException) {
                    appIcon.setImageResource(R.drawable.ic_launcher_default)
                }
            }
            if (item.status == IN_PROGRESS) {
                appInfo.visibility = GONE
                appStatus.visibility = INVISIBLE
                progressBar.visibility = VISIBLE
            } else {
                appStatus.visibility = VISIBLE
                progressBar.visibility = INVISIBLE
                appInfo.visibility = GONE
                when (item.status) {
                    SUCCEEDED -> {
                        appStatus.setImageResource(R.drawable.ic_check_green)
                    }
                    FAILED -> {
                        appStatus.setImageResource(R.drawable.ic_cancel_red)
                    }
                    else -> {
                        appStatus.setImageResource(R.drawable.ic_error_yellow)
                        appInfo.text = getInfo(item.status)
                        appInfo.visibility = VISIBLE
                    }
                }
            }
        }

        private fun getInfo(status: AppRestoreStatus): String = when(status) {
            FAILED_NO_DATA -> context.getString(R.string.restore_app_no_data)
            FAILED_NOT_ALLOWED -> context.getString(R.string.restore_app_not_allowed)
            FAILED_NOT_INSTALLED -> context.getString(R.string.restore_app_not_installed)
            FAILED_QUOTA_EXCEEDED -> context.getString(R.string.restore_app_quota_exceeded)
            else -> "Please report a bug after you read this."
        }

    }

}

internal enum class AppRestoreStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    FAILED_NO_DATA,
    FAILED_NOT_ALLOWED,
    FAILED_QUOTA_EXCEEDED,
    FAILED_NOT_INSTALLED,
}

internal data class AppRestoreResult(
        val packageName: String,
        val name: CharSequence,
        val status: AppRestoreStatus)
