package com.stevesoltys.seedvault.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView.ScaleType
import android.widget.TextView
import androidx.core.content.ContextCompat.startActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DiffUtil.DiffResult
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NOT_ALLOWED
import com.stevesoltys.seedvault.ui.AppBackupState.SUCCEEDED
import com.stevesoltys.seedvault.ui.AppViewHolder
import com.stevesoltys.seedvault.ui.toRelativeTime

internal class AppStatusAdapter(private val toggleListener: AppStatusToggleListener) :
    Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<AppListItem>()
    private var editMode = false

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is AppStatus -> 0
        is AppSectionTitle -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_app_status, parent, false)
                AppStatusViewHolder(v)
            }
            1 -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_app_section_title, parent, false)
                AppSectionTitleViewHolder(v)
            }
            else -> throw AssertionError("unknown view type")
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AppStatusViewHolder -> holder.bind(items[position] as AppStatus)
            is AppSectionTitleViewHolder -> holder.bind(items[position] as AppSectionTitle)
        }
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        notifyDataSetChanged()
    }

    fun update(newItems: List<AppListItem>, diff: DiffResult) {
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun onItemChanged(item: AppStatus) {
        val pos = items.indexOfFirst { it is AppStatus && it.packageName == item.packageName }
        if (pos != NO_POSITION) notifyItemChanged(pos, item)
    }

    class AppSectionTitleViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val titleView: TextView = v as TextView
        fun bind(item: AppSectionTitle) {
            titleView.setText(item.titleRes)
        }
    }

    inner class AppStatusViewHolder(v: View) : AppViewHolder(v) {
        fun bind(item: AppStatus) {
            appName.text = item.name
            appIcon.scaleType = if (item.isSpecial) ScaleType.CENTER else ScaleType.FIT_CENTER
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
                if (item.status == FAILED_NOT_ALLOWED) {
                    appStatus.visibility = INVISIBLE
                    progressBar.visibility = INVISIBLE
                    appInfo.visibility = GONE
                } else {
                    setState(item.status, false)
                }
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

internal class AppStatusDiff(
    private val oldItems: List<AppListItem>,
    private val newItems: List<AppListItem>,
) : DiffUtil.Callback() {

    override fun getOldListSize() = oldItems.size
    override fun getNewListSize() = newItems.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val old = oldItems[oldItemPosition]
        val new = newItems[newItemPosition]
        if (old is AppSectionTitle && new is AppSectionTitle) return old.titleRes == new.titleRes
        if (old is AppStatus && new is AppStatus) return old.packageName == new.packageName
        return false
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val old = oldItems[oldItemPosition]
        val new = newItems[newItemPosition]
        if (old is AppSectionTitle && new is AppSectionTitle) return old.titleRes == new.titleRes
        return old == new
    }
}

internal class AppStatusResult(
    val appStatusList: List<AppListItem>,
    val diff: DiffResult,
)
