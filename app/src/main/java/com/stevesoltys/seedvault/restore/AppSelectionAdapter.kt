package com.stevesoltys.seedvault.restore

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

internal data class SelectableAppItem(
    val packageName: String,
    val metadata: PackageMetadata,
    val selected: Boolean,
) {
    val name: String get() = packageName
}

internal class AppSelectionAdapter(
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
            return old.selected == new.selected
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

    internal inner class AppSelectionViewHolder(v: View) : AppViewHolder(v) {
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
            checkBox.visibility = VISIBLE
            progressBar.visibility = INVISIBLE

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
