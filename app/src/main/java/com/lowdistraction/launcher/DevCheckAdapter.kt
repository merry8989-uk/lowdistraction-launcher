package com.lowdistraction.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Sectioned label/value rows for the Dev.Check window. */
class DevCheckAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<DeviceInfo.Row>()

    fun submit(list: List<DeviceInfo.Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * Replaces a single row's value in place. Used by the live sensor read-out so
     * a changing number never disturbs the scroll position or the rest of the list.
     */
    fun updateValue(index: Int, value: String) {
        if (index !in rows.indices) return
        val current = rows[index]
        if (current.value == value) return
        rows[index] = current.copy(value = value)
        notifyItemChanged(index)
    }

    override fun getItemViewType(position: Int): Int = if (rows[position].header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val v = inflater.inflate(R.layout.item_devcheck_header, parent, false)
            HeaderHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_devcheck_row, parent, false)
            RowHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is HeaderHolder -> holder.title.text = row.label
            is RowHolder -> {
                holder.label.text = row.label
                holder.value.text = row.value
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.devcheckHeader)
    }

    class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.devcheckLabel)
        val value: TextView = view.findViewById(R.id.devcheckValue)
    }
}
