package com.stevesoltys.seedvault.restore

import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.getAppName
import com.stevesoltys.seedvault.restore.RestoreProgressAdapter.PackageViewHolder
import java.util.*

internal class RestoreProgressAdapter : Adapter<PackageViewHolder>() {

    private val items = LinkedList<String>().apply { add(MAGIC_PACKAGE_MANAGER) }
    private var isComplete = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.list_item_app_status, parent, false)
        return PackageViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        holder.bind(items[position], position == 0)
    }

    fun add(packageName: String) {
        items.addFirst(packageName)
        notifyItemInserted(0)
        notifyItemRangeChanged(1, items.size - 1)
    }

    fun setComplete() {
        isComplete = true
        notifyItemChanged(0)
    }

    inner class PackageViewHolder(v: View) : ViewHolder(v) {

        private val context = v.context
        private val pm = context.packageManager
        private val appIcon: ImageView = v.findViewById(R.id.appIcon)
        private val appName: TextView = v.findViewById(R.id.appName)
        private val appStatus: ImageView = v.findViewById(R.id.appStatus)
        private val progressBar: ProgressBar = v.findViewById(R.id.progressBar)

        init {
            appStatus.setImageResource(R.drawable.ic_check_green)
        }

        fun bind(item: String, isLast: Boolean) {
            if (item == MAGIC_PACKAGE_MANAGER) {
                appIcon.setImageDrawable(pm.getApplicationIcon("android"))
                appName.text = context.getString(R.string.restore_magic_package)
            } else {
                appIcon.setImageDrawable(pm.getApplicationIcon(item))
                appName.text = getAppName(pm, item)
            }
            if (isLast && !isComplete) {
                appStatus.visibility = INVISIBLE
                progressBar.visibility = VISIBLE
            } else {
                appStatus.visibility = VISIBLE
                progressBar.visibility = INVISIBLE
            }
        }

    }

}
