package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.WindowCompat

/**
 * Chooses which sort orders the app list offers, and explains the four that need
 * Usage access.
 */
class SortSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Usage access may have been granted while we were away.
        buildUi()
    }

    private fun buildUi() {
        val theme = Look.theme(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(44), dp(24), dp(40))
        }

        column.addView(
            TextView(this).apply {
                setText(R.string.settings_back)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { finish() }
            }
        )
        column.addView(
            TextView(this).apply {
                setText(R.string.sort_settings_title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            }
        )
        column.addView(
            TextView(this).apply {
                setText(R.string.sort_settings_intro)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, 0)
            }
        )

        // ------------------------------------------------------- the orders
        column.addView(section(getString(R.string.sort_settings_section), theme))
        val hasAccess = AppSorter.hasUsageAccess(this)
        for (mode in SortMode.entries) {
            val note = when {
                !mode.needsUsageAccess -> getString(R.string.sort_always_works)
                hasAccess -> getString(R.string.sort_needs_usage_ok)
                else -> getString(R.string.sort_needs_usage)
            }
            column.addView(
                switchRow(
                    getString(sortLabel(mode)),
                    note,
                    SortPrefs.isEnabled(this, mode),
                    theme
                ) { on -> SortPrefs.setEnabled(this, mode, on) }
            )
        }

        // ---------------------------------------------------- usage access
        column.addView(section(getString(R.string.sort_settings_usage_section), theme))
        column.addView(
            valueRow(
                getString(R.string.sort_usage_row),
                if (hasAccess) getString(R.string.sort_usage_on)
                else getString(R.string.sort_usage_off),
                theme
            ) { openUsageAccess() }
        )
        column.addView(
            TextView(this).apply {
                setText(R.string.sort_usage_desc)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, 0)
            }
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(theme.bg)
            addView(column)
        }
        setContentView(scroll)

        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = theme.light
        controller.isAppearanceLightNavigationBars = theme.light
    }

    private fun openUsageAccess() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_usage_title)
            .setMessage(R.string.sort_usage_message)
            .setPositiveButton(R.string.sort_usage_ok) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- widgets
    private fun section(title: String, theme: Theme): TextView = TextView(this).apply {
        setText(title)
        setTextColor(theme.textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
        setPadding(0, dp(26), 0, dp(4))
    }

    private fun valueRow(title: String, value: String, theme: Theme, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                setText(title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(this).apply {
                setText(value)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            }
        )
        return row
    }

    private fun switchRow(
        title: String,
        note: String,
        checked: Boolean,
        theme: Theme,
        onChange: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        texts.addView(
            TextView(this).apply {
                setText(title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.MONOSPACE
            }
        )
        texts.addView(
            TextView(this).apply {
                setText(note)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }
        )
        return row
    }

    private fun sortLabel(mode: SortMode): Int = when (mode) {
        SortMode.NAME -> R.string.sort_name
        SortMode.NAME_DESC -> R.string.sort_name_desc
        SortMode.PACKAGE -> R.string.sort_package
        SortMode.PACKAGE_DESC -> R.string.sort_package_desc
        SortMode.INSTALLED -> R.string.sort_installed
        SortMode.INSTALLED_OLDEST -> R.string.sort_installed_oldest
        SortMode.UPDATED -> R.string.sort_updated
        SortMode.MOST_OPENED -> R.string.sort_most_opened
        SortMode.LEAST_OPENED -> R.string.sort_least_opened
        SortMode.RECENTLY_USED -> R.string.sort_recent
        SortMode.LEAST_RECENTLY_USED -> R.string.sort_least_recent
        SortMode.MOST_TIME -> R.string.sort_most_time
        SortMode.LEAST_TIME -> R.string.sort_least_time
        SortMode.LARGEST -> R.string.sort_largest
        SortMode.SMALLEST -> R.string.sort_smallest
        SortMode.SHORTEST_NAME -> R.string.sort_shortest
        SortMode.LONGEST_NAME -> R.string.sort_longest
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
