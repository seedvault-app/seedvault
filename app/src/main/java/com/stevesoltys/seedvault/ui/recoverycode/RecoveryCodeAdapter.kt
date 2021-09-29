package com.stevesoltys.seedvault.ui.recoverycode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter
import com.stevesoltys.seedvault.R

class RecoveryCodeAdapter(private val items: List<CharArray>) :
    Adapter<RecoveryCodeViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecoveryCodeViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_recovery_code_output, parent, false) as View
        return RecoveryCodeViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecoveryCodeViewHolder, position: Int) {
        holder.bind(position + 1, items[position])
    }

}

class RecoveryCodeViewHolder(v: View) : RecyclerView.ViewHolder(v) {

    private val num = v.findViewById<TextView>(R.id.num)
    private val word = v.findViewById<TextView>(R.id.word)

    internal fun bind(number: Int, item: CharArray) {
        num.text = number.toString()
        word.text = String(item)
    }

}
