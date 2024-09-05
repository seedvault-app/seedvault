package com.stevesoltys.seedvault.restore

import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.ViewGroup
import android.widget.ImageView.ScaleType.CENTER
import android.widget.ImageView.ScaleType.FIT_CENTER
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.VISIBLE
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.ui.AppViewHolder
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_SYSTEM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface AppSelectionItem

internal class AppSelectionSection(@StringRes val titleRes: Int) : AppSelectionItem

internal data class SelectableAppItem(
    val packageName: String,
    val metadata: PackageMetadata,
    val selected: Boolean,
    val hasIcon: Boolean? = null,
) : AppSelectionItem {
    val name: String get() = metadata.name?.toString() ?: packageName
}

internal class AppSelectionAdapter(
    val scope: CoroutineScope,
    val iconLoader: suspend (SelectableAppItem, (Drawable) -> Unit) -> Unit,
    val listener: (SelectableAppItem) -> Unit,
) : Adapter<RecyclerView.ViewHolder>() {

    private val diffCallback = object : ItemCallback<AppSelectionItem>() {
        override fun areItemsTheSame(
            oldItem: AppSelectionItem,
            newItem: AppSelectionItem,
        ): Boolean {
            return if (oldItem is AppSelectionSection && newItem is AppSelectionSection) {
                oldItem.titleRes == newItem.titleRes
            } else if (oldItem is SelectableAppItem && newItem is SelectableAppItem) {
                oldItem.packageName == newItem.packageName
            } else {
                false
            }
        }

        override fun areContentsTheSame(
            old: AppSelectionItem,
            new: AppSelectionItem,
        ): Boolean {
            return if (old is AppSelectionSection && new is AppSelectionSection) {
                true
            } else if (old is SelectableAppItem && new is SelectableAppItem) {
                old.selected == new.selected && old.hasIcon == new.hasIcon
            } else {
                false
            }
        }
    }
    private val differ = AsyncListDiffer(this, diffCallback)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = position.toLong() // items never get added/removed

    override fun getItemViewType(position: Int): Int = when (differ.currentList[position]) {
        is SelectableAppItem -> 0
        is AppSelectionSection -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_app_status, parent, false)
                SelectableAppViewHolder(v)
            }

            1 -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_app_section_title, parent, false)
                AppSelectionSectionViewHolder(v)
            }

            else -> throw AssertionError("unknown view type")
        }
    }

    override fun getItemCount() = differ.currentList.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SelectableAppViewHolder -> {
                holder.bind(differ.currentList[position] as SelectableAppItem)
            }

            is AppSelectionSectionViewHolder -> {
                holder.bind(differ.currentList[position] as AppSelectionSection)
            }
        }
    }

    fun submitList(items: List<AppSelectionItem>) {
        val itemsWithSections = items.toMutableList().apply {
            val i = indexOfLast {
                it as SelectableAppItem
                it.packageName == PACKAGE_NAME_SYSTEM
            }
            add(i + 1, AppSelectionSection(R.string.backup_section_user))
            add(0, AppSelectionSection(R.string.backup_section_system))
        }
        differ.submitList(itemsWithSections)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is SelectableAppViewHolder) holder.iconJob?.cancel()
    }

    class AppSelectionSectionViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val titleView: TextView = v as TextView
        fun bind(item: AppSelectionSection) {
            titleView.setText(item.titleRes)
        }
    }

    internal inner class SelectableAppViewHolder(v: View) : AppViewHolder(v) {

        var iconJob: Job? = null

        fun bind(item: SelectableAppItem) {
            v.background = clickableBackground

            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.selected
            checkBox.setOnCheckedChangeListener { _, _ ->
                listener(item)
            }
            checkBox.visibility = if (item.hasIcon == null) INVISIBLE else VISIBLE
            progressBar.visibility = if (item.hasIcon == null) VISIBLE else INVISIBLE
            if (item.hasIcon == null) {
                v.setOnClickListener(null)
            } else v.setOnClickListener {
                checkBox.toggle()
            }

            val isSpecial = item.metadata.isInternalSystem
            appIcon.scaleType = FIT_CENTER
            appIcon.setImageResource(R.drawable.ic_launcher_default)
            appIcon.scaleType = if (isSpecial) CENTER else FIT_CENTER
            if (item.hasIcon == null && !isSpecial) {
                appIcon.alpha = 0.5f
            } else if (item.hasIcon == true || isSpecial) {
                appIcon.alpha = 0.5f
                iconJob = scope.launch {
                    iconLoader(item) { bitmap ->
                        appIcon.scaleType = if (isSpecial) CENTER else FIT_CENTER
                        appIcon.setImageDrawable(bitmap)
                        appIcon.alpha = 1f
                    }
                }
            } else {
                appIcon.alpha = 1f
            }
            appName.text = item.name
            val time = if (item.metadata.time > 0) DateUtils.getRelativeTimeSpanString(
                item.metadata.time,
                System.currentTimeMillis(),
                DateUtils.HOUR_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ) else v.context.getString(R.string.settings_backup_last_backup_never)
            val size = if (item.metadata.size == null) "" else "(" + Formatter.formatShortFileSize(
                v.context,
                item.metadata.size ?: 0
            ) + ")"
            appInfo.text =
                v.context.getString(R.string.settings_backup_status_summary, "$time $size")
            appInfo.visibility = VISIBLE
        }
    }

}
