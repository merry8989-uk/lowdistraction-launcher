package com.lowdistraction.launcher

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The log explorer: the plan's query engine, on the device.
 *
 * Filter by severity and category, search across everything an event says, and
 * read the counts. Nothing here leaves the phone, nothing new is collected, and
 * nothing is uploaded — it is a view over the log that is already there.
 *
 * All the deciding is in [LogFilter], which is free of Android and unit-tested.
 * This screen only draws what it decides.
 */
class LogExplorerActivity : Activity() {

    private lateinit var theme: Theme
    private lateinit var summary: TextView
    private lateinit var aggregation: TextView
    private lateinit var adapter: EventAdapter

    private var all: List<Diag.Event> = emptyList()
    private var query = LogFilter.Query()

    private val severityChips = LinkedHashMap<String, TextView>()
    private val categoryChips = LinkedHashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.installCrashHandler()
        theme = Look.theme(this)
        buildUi()
        load()
    }

    override fun onResume() {
        super.onResume()
        Events.screenView(this, "Log explorer")
    }

    // ------------------------------------------------------------ screen build
    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bg)
            setPadding(dp(18), dp(38), dp(18), 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.settings_back)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { finish() }
            }
        )
        header.addView(
            TextView(this).apply {
                text = getString(R.string.explorer_title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        column.addView(header)

        summary = TextView(this).apply {
            setTextColor(theme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(12))
        }
        column.addView(summary)

        column.addView(label(getString(R.string.explorer_severity)))
        column.addView(
            chipRow(LogFilter.SEVERITIES, severityChips) { chosen ->
                query = query.copy(severity = chosen)
                render()
            }
        )

        column.addView(label(getString(R.string.explorer_category)))
        column.addView(
            chipRow(LogFilter.CATEGORIES, categoryChips) { chosen ->
                query = query.copy(category = chosen)
                render()
            }
        )

        column.addView(label(getString(R.string.explorer_search)))
        column.addView(
            EditText(this).apply {
                hint = getString(R.string.explorer_search_hint)
                setHintTextColor(theme.textSecondary)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.MONOSPACE
                setSingleLine()
                background = GradientDrawable().apply {
                    cornerRadius = dp(11).toFloat()
                    setColor(theme.surface)
                    setStroke(dp(1), (theme.textSecondary and 0x00FFFFFF) or 0x40000000)
                }
                setPadding(dp(12), dp(11), dp(12), dp(11))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        query = query.copy(text = s?.toString() ?: "")
                        render()
                    }
                })
            }
        )

        aggregation = TextView(this).apply {
            setTextColor(theme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, dp(4))
        }
        column.addView(aggregation)

        val rows = EventAdapter()
        adapter = rows
        column.addView(
            RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@LogExplorerActivity)
                adapter = rows
                setBackgroundColor(theme.bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
        )

        column.addView(
            TextView(this).apply {
                text = getString(R.string.explorer_tap)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, dp(10))
            }
        )

        setContentView(column)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(theme.textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(10), 0, dp(5))
    }

    /** One row of chips that scrolls sideways, so a long list still fits. */
    private fun chipRow(
        values: List<String>,
        into: LinkedHashMap<String, TextView>,
        onPick: (String) -> Unit
    ): HorizontalScrollView {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (value in values) {
            val chip = TextView(this).apply {
                this.text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { onPick(value) }
            }
            into[value] = chip
            strip.addView(chip)
            strip.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), 1)
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    /** Draws the chips for the current query: the chosen one is filled in. */
    private fun paintChips() {
        for ((value, chip) in severityChips) {
            paint(chip, value == query.severity)
        }
        for ((value, chip) in categoryChips) {
            paint(chip, value == query.category)
        }
    }

    private fun paint(chip: TextView, chosen: Boolean) {
        chip.setTextColor(if (chosen) theme.bg else theme.textSecondary)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (chosen) theme.accent else theme.surface)
            setStroke(dp(1), (theme.textSecondary and 0x00FFFFFF) or 0x40000000)
        }
    }

    // ---------------------------------------------------------------- loading
    private fun load() {
        Thread {
            val events = runCatching {
                Diag.flush()
                Diag.entries(this)
            }.getOrDefault(emptyList())
            runOnUiThread {
                all = events
                render()
            }
        }.start()
    }

    private fun render() {
        val shown = LogFilter.apply(all, query)
        summary.text =
            if (all.isEmpty()) {
                getString(R.string.explorer_empty)
            } else {
                LogFilter.summary(shown.size, all.size, query)
            }
        aggregation.text = aggregationText()
        paintChips()
        adapter.submit(shown)
    }

    /**
     * The shape of the whole log, so the filters have something to aim at. Counts
     * are over everything recorded, not over what is showing, and the label says
     * so.
     */
    private fun aggregationText(): String {
        val bySeverity = LogFilter.bySeverity(all)
            .filter { it.second > 0 }
            .joinToString("  ") { it.first + " " + it.second }
        val byCategory = LogFilter.byCategory(all)
            .filter { it.second > 0 }
            .joinToString("  ") { it.first + " " + it.second }
        return "whole log\nseverity  " + bySeverity.ifEmpty { "—" } +
            "\ncategory  " + byCategory.ifEmpty { "—" }
    }

    // ---------------------------------------------------------------- the list
    /**
     * One event per row: a summary line always, and everything it recorded when
     * tapped. Expansion is keyed by the event itself rather than by its position,
     * so changing a filter does not shuffle which rows are open.
     */
    private inner class EventAdapter : RecyclerView.Adapter<EventAdapter.Holder>() {

        private var rows: List<Diag.Event> = emptyList()
        private val expanded = HashSet<String>()

        fun submit(items: List<Diag.Event>) {
            rows = items
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val column = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(9), dp(2), dp(9))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                // The platform's own touch feedback, so a tap is visible.
                val feedback = TypedValue()
                if (context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, feedback, true
                    )
                ) {
                    setBackgroundResource(feedback.resourceId)
                }
            }
            val head = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            val detail = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            column.addView(head)
            column.addView(detail)
            return Holder(column, head, detail)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val event = rows[position]
            val key = keyOf(event)
            val open = key in expanded

            holder.head.text = headLine(event)
            holder.head.setTextColor(colourFor(event.level))
            holder.detail.text = detailText(event)
            holder.detail.visibility = if (open) View.VISIBLE else View.GONE
            holder.root.setOnClickListener {
                if (key in expanded) expanded.remove(key) else expanded.add(key)
                notifyItemChanged(position)
            }
        }

        inner class Holder(
            val root: LinearLayout,
            val head: TextView,
            val detail: TextView
        ) : RecyclerView.ViewHolder(root)
    }

    /** Time without the year, the severity, and the event name. */
    private fun headLine(e: Diag.Event): String {
        val stamp = LogFormat.stamp(e.time)
        val short = if (stamp.length > 5) stamp.substring(5) else stamp
        return short + "  " + e.level.padEnd(5) + " " + e.name
    }

    /**
     * Everything the event recorded, grouped the way it is stored — the plan's
     * context, correlation, error, metrics and attributes.
     */
    private fun detailText(e: Diag.Event): String {
        val sb = StringBuilder()
        sb.append("screen ").append(e.screen)
        if (e.session.isNotEmpty()) sb.append("   session ").append(e.session)
        sb.append('\n')
        for (group in listOf(
            LogGroups.CONTEXT, LogGroups.CORRELATION, LogGroups.ERROR,
            LogGroups.METRICS, LogGroups.ATTRIBUTES
        )) {
            val mine = e.props.filter { LogGroups.of(it.key) == group }
            if (mine.isEmpty()) continue
            sb.append(group).append('\n')
            for ((key, value) in mine) {
                sb.append("  ").append(key).append(" = ").append(value).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** Severity in colour, worked out from the theme so both modes read well. */
    private fun colourFor(level: String): Int = when (level) {
        "FATAL", "ERROR" ->
            Look.hsv(0f, if (theme.light) 0.74f else 0.52f, if (theme.light) 0.50f else 0.94f)
        "WARN" ->
            Look.hsv(38f, if (theme.light) 0.80f else 0.48f, if (theme.light) 0.52f else 0.94f)
        "DEBUG", "TRACE" -> theme.textSecondary
        else -> theme.textPrimary
    }

    private fun keyOf(e: Diag.Event): String = e.time.toString() + "|" + e.name + "|" + e.session

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
