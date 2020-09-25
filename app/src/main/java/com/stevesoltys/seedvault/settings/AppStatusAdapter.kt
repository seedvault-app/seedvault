package com.stevesoltys.seedvault.settings

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.core.content.ContextCompat.startActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DiffUtil.DiffResult
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.AppRestoreStatus
import com.stevesoltys.seedvault.restore.AppRestoreStatus.SUCCEEDED
import com.stevesoltys.seedvault.settings.AppStatusAdapter.AppStatusViewHolder
import com.stevesoltys.seedvault.ui.AppViewHolder
import com.stevesoltys.seedvault.ui.toRelativeTime

internal class AppStatusAdapter(private val toggleListener: AppStatusToggleListener) : Adapter<AppStatusViewHolder>() {

    private val items = ArrayList<AppStatus>()
    private var editMode = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppStatusViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.list_item_app_status, parent, false)
        return AppStatusViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: AppStatusViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        notifyDataSetChanged()
    }

    fun update(newItems: List<AppStatus>, diff: DiffResult) {
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun onItemChanged(item: AppStatus) {
        val pos = items.indexOfFirst { it.packageName == item.packageName }
        if (pos != NO_POSITION) notifyItemChanged(pos, item)
    }

    inner class AppStatusViewHolder(v: View) : AppViewHolder(v) {
        fun bind(item: AppStatus) {
            appName.text = item.name
            appIcon.setImageDrawable(item.icon)
            v.background = clickableBackground
            if (editMode) {
                v.setOnClickListener {
                    switchView.toggle()
                    item.enabled = switchView.isChecked
                    toggleListener.onAppStatusToggled(item)
                }
                appInfo.visibility = GONE
                appStatus.visibility = INVISIBLE
                progressBar.visibility = INVISIBLE
                switchView.visibility = VISIBLE
                switchView.isChecked = item.enabled
            } else {
                v.setOnClickListener(null)
                v.setOnLongClickListener {
                    val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", item.packageName, null)
                    }
                    startActivity(context, intent, null)
                    true
                }
                setStatus(item.status)
                if (item.status == SUCCEEDED) {
                    appInfo.text = item.time.toRelativeTime(context)
                    appInfo.visibility = VISIBLE
                }
                switchView.visibility = INVISIBLE
            }
            // show disabled items differently
            showEnabled(item.enabled)
        }

        private fun showEnabled(enabled: Boolean) {
            val alpha = if (enabled) 1.0f else 0.5f
            // setting the alpha on root view v only doesn't work as the ItemAnimator messes with it
            appIcon.alpha = alpha
            appName.alpha = alpha
            appInfo.alpha = alpha
            appStatus.alpha = alpha
        }
    }

}

data class AppStatus(
        val packageName: String,
        var enabled: Boolean,
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
