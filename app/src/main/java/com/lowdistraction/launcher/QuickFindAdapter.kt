package com.lowdistraction.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Text-only rows for the second (system) search bar. */
class QuickFindAdapter(
    private val onClick: (QuickEntry) -> Unit
) : RecyclerView.Adapter<QuickFindAdapter.ViewHolder>() {

    private val items = ArrayList<QuickEntry>()

    fun submit(list: List<QuickEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.label.text = entry.label
        holder.hint.text = entry.hint
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.quickLabel)
        val hint: TextView = view.findViewById(R.id.quickHint)
    }
}
