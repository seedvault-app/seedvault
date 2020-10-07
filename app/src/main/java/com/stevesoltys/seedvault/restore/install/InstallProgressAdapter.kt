package com.stevesoltys.seedvault.restore.install

import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.ui.AppViewHolder

internal class InstallProgressAdapter : Adapter<AppInstallViewHolder>() {

    private val items = SortedList<ApkInstallResult>(
        ApkInstallResult::class.java,
        object : SortedListAdapterCallback<ApkInstallResult>(this) {
            override fun areItemsTheSame(item1: ApkInstallResult, item2: ApkInstallResult) =
                item1.packageName == item2.packageName

            override fun areContentsTheSame(oldItem: ApkInstallResult, newItem: ApkInstallResult) =
                oldItem == newItem

            override fun compare(item1: ApkInstallResult, item2: ApkInstallResult) =
                item1.compareTo(item2)
        })

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppInstallViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_app_status, parent, false)
        return AppInstallViewHolder(v)
    }

    override fun getItemCount() = items.size()

    override fun onBindViewHolder(holder: AppInstallViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun update(items: Collection<ApkInstallResult>) {
        this.items.replaceAll(items)
    }
}

internal class AppInstallViewHolder(v: View) : AppViewHolder(v) {

    fun bind(item: ApkInstallResult) {
        appIcon.setImageDrawable(item.icon)
        appName.text = item.name
        when (item.state) {
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
                appStatus.setImageResource(R.drawable.ic_error_red)
                appStatus.visibility = VISIBLE
                progressBar.visibility = INVISIBLE
            }
            QUEUED -> throw AssertionError()
        }
    }

}
