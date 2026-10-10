package com.lowdistraction.launcher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * The app list. Each row can show the name only, the icon only, or both, and
 * every part of that is sized, shaped and coloured from [Look].
 */
class AppListAdapter(
    private val onLaunch: (AppInfo) -> Unit,
    private val onLongPress: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val items = ArrayList<AppInfo>()
    private val icons = LruCache<String, Drawable>(240)

    private var ready = false
    private var mode = DisplayMode.NAME
    private var iconPx = 0
    private var shape = IconShape.ROUNDED
    private var outlinePx = 0f
    private var outlineColor = 0
    private var nameSp = 18f
    private var bold = false
    private var spacing = 0f
    private var textColor = 0
    private var gapPx = 0

    fun submit(list: List<AppInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Re-reads every appearance setting. Safe to call on each resume. */
    fun refreshLook(context: Context) {
        val metrics = context.resources.displayMetrics
        val theme = Look.theme(context)
        mode = Look.displayMode(context)
        iconPx = (Look.iconSize(context) * metrics.density).toInt()
        shape = Look.iconShape(context)
        outlinePx = Look.iconOutline(context) * metrics.density
        outlineColor = theme.accent
        nameSp = Look.nameSize(context).toFloat()
        bold = Look.nameBold(context)
        spacing = Look.nameSpacing(context) / 100f
        textColor = theme.textPrimary
        gapPx = (14 * metrics.density).toInt()
        icons.evictAll()
        ready = true
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        val context = holder.itemView.context
        if (!ready) refreshLook(context)

        holder.label.text = app.label
        holder.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp)
        holder.label.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        holder.label.letterSpacing = spacing
        holder.label.setTextColor(textColor)

        when (mode) {
            DisplayMode.NAME -> {
                holder.icon.visibility = View.GONE
                holder.label.visibility = View.VISIBLE
                holder.row.gravity = Gravity.CENTER_VERTICAL
                setLabelGap(holder, 0)
            }
            DisplayMode.ICON -> {
                holder.icon.visibility = View.VISIBLE
                holder.label.visibility = View.GONE
                holder.row.gravity = Gravity.CENTER_HORIZONTAL
                setIconSize(holder)
            }
            DisplayMode.ICON_NAME -> {
                holder.icon.visibility = View.VISIBLE
                holder.label.visibility = View.VISIBLE
                holder.row.gravity = Gravity.CENTER_VERTICAL
                setIconSize(holder)
                setLabelGap(holder, gapPx)
            }
        }

        if (holder.icon.visibility == View.VISIBLE) {
            holder.icon.setImageDrawable(iconFor(context, app))
        } else {
            holder.icon.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener { onLaunch(app) }
        holder.itemView.setOnLongClickListener { view ->
            onLongPress(app, view)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    private fun setIconSize(holder: ViewHolder) {
        val lp = holder.icon.layoutParams as LinearLayout.LayoutParams
        if (lp.width != iconPx || lp.height != iconPx) {
            lp.width = iconPx
            lp.height = iconPx
            holder.icon.layoutParams = lp
        }
    }

    private fun setLabelGap(holder: ViewHolder, px: Int) {
        val lp = holder.label.layoutParams as LinearLayout.LayoutParams
        if (lp.marginStart != px) {
            lp.marginStart = px
            holder.label.layoutParams = lp
        }
    }

    private fun iconFor(context: Context, app: AppInfo): Drawable? {
        icons.get(app.packageName)?.let { return it }
        val base = runCatching {
            context.packageManager.getApplicationIcon(app.packageName)
        }.getOrNull() ?: return null
        val shaped = ShapedIconDrawable(base, shape, outlinePx, outlineColor)
        icons.put(app.packageName, shaped)
        return shaped
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view.findViewById(R.id.appRow)
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }
}
