package com.stevesoltys.seedvault.restore

import android.content.pm.PackageManager.NameNotFoundException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView.Adapter
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.RestoreProgressAdapter.PackageViewHolder
import com.stevesoltys.seedvault.ui.AppViewHolder
import java.util.LinkedList

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

    class PackageViewHolder(v: View) : AppViewHolder(v) {
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
            setStatus(item.status)
        }
    }

}

enum class AppRestoreStatus {
    IN_PROGRESS,
    SUCCEEDED,
    NOT_YET_BACKED_UP,
    FAILED,
    FAILED_NO_DATA,
    FAILED_WAS_STOPPED,
    FAILED_NOT_ALLOWED,
    FAILED_QUOTA_EXCEEDED,
    FAILED_NOT_INSTALLED,
}

internal data class AppRestoreResult(
        val packageName: String,
        val name: CharSequence,
        val status: AppRestoreStatus)
