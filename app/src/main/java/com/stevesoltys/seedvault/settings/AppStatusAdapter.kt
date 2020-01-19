package com.stevesoltys.seedvault.settings

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DiffUtil.DiffResult
import androidx.recyclerview.widget.RecyclerView.Adapter
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.AppRestoreStatus
import com.stevesoltys.seedvault.restore.AppRestoreStatus.SUCCEEDED
import com.stevesoltys.seedvault.settings.AppStatusAdapter.AppStatusViewHolder
import com.stevesoltys.seedvault.ui.AppViewHolder
import com.stevesoltys.seedvault.ui.toRelativeTime

internal class AppStatusAdapter : Adapter<AppStatusViewHolder>() {

    private val items = ArrayList<AppStatus>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppStatusViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.list_item_app_status, parent, false)
        return AppStatusViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: AppStatusViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun update(newItems: List<AppStatus>, diff: DiffResult) {
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    inner class AppStatusViewHolder(v: View) : AppViewHolder(v) {
        fun bind(item: AppStatus) {
            appName.text = item.name
            appIcon.setImageDrawable(item.icon)
            setStatus(item.status)
            if (item.status == SUCCEEDED) {
                appInfo.text = item.time.toRelativeTime(context)
                appInfo.visibility = VISIBLE
            }
        }
    }

}

internal data class AppStatus(
        val packageName: String,
        val icon: Drawable,
        val name: String,
        val time: Long,
        val status: AppRestoreStatus)

internal class AppStatusDiff(
        private val oldItems: List<AppStatus>,
        private val newItems: List<AppStatus>) : DiffUtil.Callback() {

    override fun getOldListSize() = oldItems.size
    override fun getNewListSize() = newItems.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldItems[oldItemPosition].packageName == newItems[newItemPosition].packageName
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldItems[oldItemPosition] == newItems[newItemPosition]
    }
}

internal class AppStatusResult(
        val appStatusList: List<AppStatus>,
        val diff: DiffResult
)
