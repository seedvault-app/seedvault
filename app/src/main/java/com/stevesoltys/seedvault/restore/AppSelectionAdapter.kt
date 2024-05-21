package com.stevesoltys.seedvault.restore

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.VISIBLE
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.restore.AppSelectionAdapter.AppSelectionViewHolder
import com.stevesoltys.seedvault.ui.AppViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class SelectableAppItem(
    val packageName: String,
    val metadata: PackageMetadata,
    val selected: Boolean,
    val hasIcon: Boolean? = null,
) {
    val name: String get() = packageName
}

internal class AppSelectionAdapter(
    val scope: CoroutineScope,
    val iconLoader: suspend (String, (Bitmap) -> Unit) -> Unit,
    val listener: (SelectableAppItem) -> Unit,
) : Adapter<AppSelectionViewHolder>() {

    private val diffCallback = object : ItemCallback<SelectableAppItem>() {
        override fun areItemsTheSame(
            oldItem: SelectableAppItem,
            newItem: SelectableAppItem,
        ): Boolean = oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(
            old: SelectableAppItem,
            new: SelectableAppItem,
        ): Boolean {
            return old.selected == new.selected && old.hasIcon == new.hasIcon
        }
    }
    private val differ = AsyncListDiffer(this, diffCallback)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = position.toLong() // items never get added/removed

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppSelectionViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_app_status, parent, false)
        return AppSelectionViewHolder(v)
    }

    override fun getItemCount() = differ.currentList.size

    override fun onBindViewHolder(holder: AppSelectionViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
    }

    fun submitList(items: List<SelectableAppItem>) {
        differ.submitList(items)
    }

    override fun onViewRecycled(holder: AppSelectionViewHolder) {
        holder.iconJob?.cancel()
    }

    internal inner class AppSelectionViewHolder(v: View) : AppViewHolder(v) {

        var iconJob: Job? = null

        fun bind(item: SelectableAppItem) {
            v.background = clickableBackground
            v.setOnClickListener {
                checkBox.toggle()
            }

            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.selected
            checkBox.setOnCheckedChangeListener { _, _ ->
                listener(item)
            }
            checkBox.visibility = if (item.hasIcon == null) INVISIBLE else VISIBLE
            progressBar.visibility = if (item.hasIcon == null) VISIBLE else INVISIBLE

            appIcon.setImageResource(R.drawable.ic_launcher_default)
            if (item.hasIcon == null) {
                appIcon.alpha = 0.5f
            } else if (item.hasIcon) {
                appIcon.alpha = 0.5f
                iconJob = scope.launch {
                    iconLoader(item.packageName) { bitmap ->
                        appIcon.setImageBitmap(bitmap)
                        appIcon.alpha = 1f
                    }
                }
            } else {
                appIcon.alpha = 1f
            }
            appIcon.setImageResource(R.drawable.ic_launcher_default)
            appName.text = item.packageName
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
