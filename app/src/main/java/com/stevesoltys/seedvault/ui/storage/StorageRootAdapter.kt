package com.stevesoltys.seedvault.ui.storage

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.storage.StorageRootAdapter.StorageRootViewHolder

internal class StorageRootAdapter(
    private val isRestore: Boolean,
    private val listener: StorageRootClickedListener
) : Adapter<StorageRootViewHolder>() {

    private val items = ArrayList<StorageRoot>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StorageRootViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_storage_root, parent, false) as View
        return StorageRootViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: StorageRootViewHolder, position: Int) {
        holder.bind(items[position])
    }

    internal fun setItems(items: List<StorageRoot>) {
        this.items.clear()
        this.items.addAll(items)
        notifyDataSetChanged()
    }

    internal inner class StorageRootViewHolder(private val v: View) : ViewHolder(v) {

        private val iconView = v.findViewById<ImageView>(R.id.iconView)
        private val titleView = v.findViewById<TextView>(R.id.titleView)
        private val summaryView = v.findViewById<TextView>(R.id.summaryView)

        internal fun bind(item: StorageRoot) {
            if (item.enabled) {
                v.isEnabled = true
                v.alpha = 1f
            } else {
                v.isEnabled = false
                v.alpha = 0.3f
            }

            iconView.setImageDrawable(item.icon)
            titleView.text = item.title
            when {
                item.summary != null -> {
                    summaryView.text = item.summary
                    summaryView.visibility = VISIBLE
                }
                item.availableBytes != null -> {
                    val str = Formatter.formatFileSize(v.context, item.availableBytes)
                    summaryView.text = v.context.getString(R.string.storage_available_bytes, str)
                    summaryView.visibility = VISIBLE
                }
                else -> summaryView.visibility = GONE
            }
            v.setOnClickListener {
                if (item.overrideClickListener != null) {
                    item.overrideClickListener.invoke()
                } else if (!isRestore && item.isInternal()) {
                    showWarningDialog(v.context, item)
                } else {
                    listener.onClick(item)
                }
            }
        }

    }

    private fun showWarningDialog(context: Context, item: StorageRoot) {
        AlertDialog.Builder(context)
            .setTitle(R.string.storage_internal_warning_title)
            .setMessage(R.string.storage_internal_warning_message)
            .setPositiveButton(R.string.storage_internal_warning_choose_other) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(R.string.storage_internal_warning_use_anyway) { dialog, _ ->
                dialog.dismiss()
                listener.onClick(item)
            }
            .show()
    }

}
