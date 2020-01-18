package com.stevesoltys.seedvault.restore

import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.transport.restore.ApkRestoreResult
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.FAILED
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.IN_PROGRESS
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.QUEUED
import com.stevesoltys.seedvault.transport.restore.ApkRestoreStatus.SUCCEEDED
import com.stevesoltys.seedvault.ui.AppViewHolder

internal class InstallProgressAdapter : Adapter<AppInstallViewHolder>() {

    private val items = SortedList<ApkRestoreResult>(ApkRestoreResult::class.java, object : SortedListAdapterCallback<ApkRestoreResult>(this) {
        override fun areItemsTheSame(item1: ApkRestoreResult, item2: ApkRestoreResult) = item1.packageName == item2.packageName
        override fun areContentsTheSame(oldItem: ApkRestoreResult, newItem: ApkRestoreResult) = oldItem == newItem
        override fun compare(item1: ApkRestoreResult, item2: ApkRestoreResult) = item1.compareTo(item2)
    })

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppInstallViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.list_item_app_status, parent, false)
        return AppInstallViewHolder(v)
    }

    override fun getItemCount() = items.size()

    override fun onBindViewHolder(holder: AppInstallViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun update(items: Collection<ApkRestoreResult>) {
        this.items.replaceAll(items)
    }
}

internal class AppInstallViewHolder(v: View) : AppViewHolder(v) {

    fun bind(item: ApkRestoreResult) {
        appIcon.setImageDrawable(item.icon)
        appName.text = item.name
        when (item.status) {
            IN_PROGRESS -> {
                appStatus.visibility = INVISIBLE
                progressBar.visibility = VISIBLE
            }
            SUCCEEDED -> {
                appStatus.setImageResource(R.drawable.ic_check_green)
                appStatus.visibility = VISIBLE
                progressBar.visibility = INVISIBLE
            }
            FAILED -> {
                appStatus.setImageResource(R.drawable.ic_cancel_red)
                appStatus.visibility = VISIBLE
                progressBar.visibility = INVISIBLE
            }
            QUEUED -> throw AssertionError()
        }
    }

}
