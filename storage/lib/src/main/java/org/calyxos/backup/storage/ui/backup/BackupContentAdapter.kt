package org.calyxos.backup.storage.ui.backup

import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.api.EXTERNAL_STORAGE_PROVIDER_AUTHORITY

internal class BackupContentAdapter(private val listener: ContentClickListener) :
    Adapter<BackupContentAdapter.ViewHolder>() {

    private val items = ArrayList<BackupContentItem>()

    override fun getItemViewType(position: Int): Int {
        return when (items[position].uri.authority) {
            MediaStore.AUTHORITY -> R.layout.item_media
            EXTERNAL_STORAGE_PROVIDER_AUTHORITY -> R.layout.item_custom
            else -> throw IllegalStateException("unknown item type")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return when (viewType) {
            R.layout.item_media -> MediaHolder(v)
            R.layout.item_custom -> CustomHolder(v)
            else -> throw IllegalStateException("unknown view type")
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    internal fun setItems(items: List<BackupContentItem>) {
        this.items.clear()
        this.items.addAll(items)
        notifyDataSetChanged()
    }

    internal abstract class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        protected val layout: ViewGroup = view.findViewById(R.id.layout)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)

        open fun bind(item: BackupContentItem) {
            icon.setImageResource(item.contentType.drawableRes)
            title.text = item.getName(title.context)
        }
    }

    internal inner class MediaHolder(view: View) : ViewHolder(view) {
        private val switch: SwitchCompat = view.findViewById(R.id.switchView)

        override fun bind(item: BackupContentItem) {
            super.bind(item)
            layout.setOnClickListener {
                listener.onContentClicked(layout, item)
            }
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = item.enabled
            switch.setOnCheckedChangeListener { _, isChecked ->
                switch.isChecked = listener.onMediaContentEnabled(item, isChecked)
            }
        }
    }

    internal inner class CustomHolder(view: View) : ViewHolder(view) {
        private val overflow: ImageButton = view.findViewById(R.id.overflow)

        override fun bind(item: BackupContentItem) {
            super.bind(item)
            layout.setOnClickListener {
                listener.onContentClicked(overflow, item)
            }
            overflow.setOnClickListener {
                listener.onFolderOverflowClicked(overflow, item)
            }
        }
    }

}
