package com.stevesoltys.backup.restore

import android.app.backup.RestoreSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.stevesoltys.backup.R
import com.stevesoltys.backup.restore.RestoreSetAdapter.RestoreSetViewHolder

internal class RestoreSetAdapter(
        private val listener: RestoreSetClickListener,
        private val items: Array<out RestoreSet>) : Adapter<RestoreSetViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RestoreSetViewHolder {
        val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_item_restore_set, parent, false) as View
        return RestoreSetViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RestoreSetViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class RestoreSetViewHolder(private val v: View) : ViewHolder(v) {

        private val titleView = v.findViewById<TextView>(R.id.titleView)
        private val subtitleView = v.findViewById<TextView>(R.id.subtitleView)

        internal fun bind(item: RestoreSet) {
            v.setOnClickListener { listener.onRestoreSetClicked(item) }
            titleView.text = item.name
            subtitleView.text = item.device
        }

    }

}
