package com.lowdistraction.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Minimal text-only list adapter. One row = one app name.
 * Tap launches, long-press opens the per-app menu.
 */
class AppListAdapter(
    private val onLaunch: (AppInfo) -> Unit,
    private val onLongPress: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val items = ArrayList<AppInfo>()

    fun submit(list: List<AppInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.label.text = app.label
        holder.itemView.setOnClickListener { onLaunch(app) }
        holder.itemView.setOnLongClickListener { view ->
            onLongPress(app, view)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.appLabel)
    }
}
