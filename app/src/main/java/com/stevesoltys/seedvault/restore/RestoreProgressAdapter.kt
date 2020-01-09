package com.stevesoltys.seedvault.restore

import android.content.pm.PackageManager.NameNotFoundException
import android.view.LayoutInflater
import android.view.View
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
import com.stevesoltys.seedvault.getAppName
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
        private val appStatus: ImageView = v.findViewById(R.id.appStatus)
        private val progressBar: ProgressBar = v.findViewById(R.id.progressBar)

        fun bind(item: AppRestoreResult) {
            if (item.packageName == MAGIC_PACKAGE_MANAGER) {
                appIcon.setImageResource(R.drawable.ic_launcher_default)
                appName.text = context.getString(R.string.restore_magic_package)
            } else {
                try {
                    appIcon.setImageDrawable(pm.getApplicationIcon(item.packageName))
                } catch (e: NameNotFoundException) {
                    appIcon.setImageResource(R.drawable.ic_launcher_default)
                }
                appName.text = getAppName(pm, item.packageName)
            }
            if (item.status == IN_PROGRESS) {
                appStatus.visibility = INVISIBLE
                progressBar.visibility = VISIBLE
            } else {
                appStatus.setImageResource(
                        if (item.status == SUCCEEDED) R.drawable.ic_check_green
                        else R.drawable.ic_cancel_red
                )
                appStatus.visibility = VISIBLE
                progressBar.visibility = INVISIBLE
            }
        }

    }

}

internal enum class AppRestoreStatus { IN_PROGRESS, SUCCEEDED, FAILED }

internal data class AppRestoreResult(val packageName: String, val status: AppRestoreStatus)
