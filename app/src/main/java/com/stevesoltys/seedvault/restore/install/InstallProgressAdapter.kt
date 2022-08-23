package com.stevesoltys.seedvault.restore.install

import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.SortedList
import androidx.recyclerview.widget.SortedListAdapterCallback
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED_SYSTEM_APP
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.SUCCEEDED
import com.stevesoltys.seedvault.ui.AppViewHolder
import com.stevesoltys.seedvault.ui.notification.getAppName

internal interface InstallItemListener {
    fun onFailedItemClicked(item: ApkInstallResult)
}

internal class InstallProgressAdapter(
    private val listener: InstallItemListener,
) : Adapter<InstallProgressAdapter.AppInstallViewHolder>() {

    private var finished = false
    private val finishedComparator = FailedFirstComparator()
    private val items = SortedList(
        ApkInstallResult::class.java,
        object : SortedListAdapterCallback<ApkInstallResult>(this) {
            override fun areItemsTheSame(item1: ApkInstallResult, item2: ApkInstallResult) =
                item1.packageName == item2.packageName

            override fun areContentsTheSame(old: ApkInstallResult, new: ApkInstallResult): Boolean {
                // update failed items when finished
                return if (finished) new.state != FAILED && old == new
                else old == new
            }

            override fun compare(item1: ApkInstallResult, item2: ApkInstallResult): Int {
                return if (finished) finishedComparator.compare(item1, item2)
                else item1.compareTo(item2)
            }
        }
    )

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

    fun setFinished() {
        finished = true
    }

    internal inner class AppInstallViewHolder(v: View) : AppViewHolder(v) {

        fun bind(item: ApkInstallResult) {
            v.setOnClickListener(null)
            v.background = null

            appIcon.setImageDrawable(item.icon)
            appName.text = item.name ?: getAppName(v.context, item.packageName.toString())
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
