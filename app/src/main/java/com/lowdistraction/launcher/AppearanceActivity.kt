package com.lowdistraction.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The appearance screen: how the app list is drawn, how big and bold the icons
 * and names are, and which of the 60+ colour themes (or a custom one) is used.
 */
class AppearanceActivity : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ------------------------------------------------------------- screen build
    private fun buildUi() {
        val theme = Look.theme(this)
        val density = resources.displayMetrics.density

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(44), dp(24), dp(40))
        }

        val back = TextView(this).apply {
            text = getString(R.string.settings_back)
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { finish() }
        }
        column.addView(back)

        column.addView(
            TextView(this).apply {
                text = getString(R.string.appearance_title)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            }
        )

        column.addView(
            previewCard(theme),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        // ---------------------------------------------------------- display
        column.addView(section(getString(R.string.appearance_section_display), theme))
        column.addView(
            valueRow(
                getString(R.string.appearance_row_style),
                getString(styleLabel(Look.displayMode(this))),
                theme
            ) { showStyleDialog() }
        )

        // ------------------------------------------------------------ icons
        column.addView(section(getString(R.string.appearance_section_icons), theme))
        column.addView(
            valueRow(
                getString(R.string.appearance_row_icon_size),
                getString(R.string.appearance_dp, Look.iconSize(this)),
                theme
            ) { showIconSizeSlider() }
        )
        column.addView(
            valueRow(
                getString(R.string.appearance_row_icon_shape),
                getString(shapeLabel(Look.iconShape(this))),
                theme
            ) { showShapeDialog() }
        )
        column.addView(
            valueRow(
                getString(R.string.appearance_row_icon_outline),
                if (Look.iconOutline(this) == 0) getString(R.string.appearance_none)
                else getString(R.string.appearance_dp, Look.iconOutline(this)),
                theme
            ) { showOutlineSlider() }
        )

        // ------------------------------------------------------------ names
        column.addView(section(getString(R.string.appearance_section_names), theme))
        column.addView(
            valueRow(
                getString(R.string.appearance_row_name_size),
                getString(R.string.appearance_sp, Look.nameSize(this)),
                theme
            ) { showNameSizeSlider() }
        )
        column.addView(
            switchRow(
                getString(R.string.appearance_row_name_bold),
                getString(R.string.appearance_row_name_bold_desc),
                Look.nameBold(this),
                theme
            ) { Look.setNameBold(this, it); buildUi() }
        )
        column.addView(
            valueRow(
                getString(R.string.appearance_row_name_spacing),
                getString(R.string.appearance_em, Look.nameSpacing(this)),
                theme
            ) { showSpacingSlider() }
        )

        // ------------------------------------------------------------ theme
        column.addView(section(getString(R.string.appearance_section_theme), theme))
        column.addView(
            valueRow(
                getString(R.string.appearance_row_theme),
                theme.name,
                theme
            ) { showThemeDialog() }
        )
        column.addView(
            switchRow(
                getString(R.string.appearance_row_glow),
                getString(R.string.appearance_row_glow_desc),
                Look.glowEnabled(this),
                theme
            ) { Look.setGlowEnabled(this, it); buildUi() }
        )
        column.addView(
            valueRow(
                getString(R.string.appearance_row_custom),
                getString(R.string.appearance_row_custom_desc),
                theme
            ) { showCustomThemeDialog() }
        )
        column.addView(
            valueRow(
                getString(R.string.appearance_row_reset),
                getString(R.string.appearance_row_reset_desc),
                theme
            ) {
                Look.reset(this)
                buildUi()
                Toast.makeText(this, R.string.appearance_reset_done, Toast.LENGTH_SHORT).show()
            }
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(theme.bg)
            addView(column)
        }
        setContentView(scroll)
        applySystemBars(theme)
    }

    /** Keeps the status-bar icons readable on light themes. */
    private fun applySystemBars(theme: Theme) {
        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = theme.light
        controller.isAppearanceLightNavigationBars = theme.light
    }

    // ---------------------------------------------------------------- widgets
    private fun previewCard(theme: Theme): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(theme.surface)
                setStroke(dp(1), Look.blend(theme.surface, theme.textSecondary, 0.35f))
            }
        }

        val icon = ImageView(this)
        val size = dp(Look.iconSize(this))
        card.addView(icon, LinearLayout.LayoutParams(size, size))
        runCatching {
            val base = packageManager.getApplicationIcon(packageName)
            icon.setImageDrawable(
                ShapedIconDrawable(
                    base,
                    Look.iconShape(this),
                    Look.iconOutline(this) * resources.displayMetrics.density,
                    theme.accent
                )
            )
        }

        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        names.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Look.nameSize(this@AppearanceActivity).toFloat())
                typeface = Typeface.MONOSPACE
                if (Look.nameBold(this@AppearanceActivity)) {
                    setTypeface(typeface, Typeface.BOLD)
                }
                letterSpacing = Look.nameSpacing(this@AppearanceActivity) / 100f
                maxLines = 1
            }
        )
        names.addView(
            TextView(this).apply {
                text = getString(R.string.appearance_preview_sub)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            }
        )
        card.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun section(title: String, theme: Theme): TextView =
        TextView(this).apply {
            text = title
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
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                text = title
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(this).apply {
                text = value
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            }
        )
        return row
    }

    private fun switchRow(
        title: String,
        desc: String,
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
            setPadding(0, dp(14), 0, dp(14))
        }
        texts.addView(
            TextView(this).apply {
                text = title
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.MONOSPACE
            }
        )
        texts.addView(
            TextView(this).apply {
                text = desc
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(toggle)
        return row
    }

    private fun swatch(color: Int, border: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(color)
            setStroke(dp(1), border)
        }
    }

    // ---------------------------------------------------------------- dialogs
    private fun showStyleDialog() {
        val modes = DisplayMode.entries
        val labels = modes.map { getString(styleLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance_row_style)
            .setSingleChoiceItems(labels, modes.indexOf(Look.displayMode(this))) { dialog, which ->
                Look.setDisplayMode(this, modes[which])
                dialog.dismiss()
                buildUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showShapeDialog() {
        val shapes = IconShape.entries
        val labels = shapes.map { getString(shapeLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance_row_icon_shape)
            .setSingleChoiceItems(labels, shapes.indexOf(Look.iconShape(this))) { dialog, which ->
                Look.setIconShape(this, shapes[which])
                dialog.dismiss()
                buildUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showIconSizeSlider() = slider(
        getString(R.string.appearance_row_icon_size),
        Look.MIN_ICON, Look.MAX_ICON, Look.iconSize(this)
    ) {
        Look.setIconSize(this, it)
        buildUi()
    }

    private fun showOutlineSlider() = slider(
        getString(R.string.appearance_row_icon_outline),
        0, Look.MAX_OUTLINE, Look.iconOutline(this)
    ) {
        Look.setIconOutline(this, it)
        buildUi()
    }

    private fun showNameSizeSlider() = slider(
        getString(R.string.appearance_row_name_size),
        Look.MIN_NAME, Look.MAX_NAME, Look.nameSize(this)
    ) {
        Look.setNameSize(this, it)
        buildUi()
    }

    private fun showSpacingSlider() = slider(
        getString(R.string.appearance_row_name_spacing),
        0, 20, Look.nameSpacing(this)
    ) {
        Look.setNameSpacing(this, it)
        buildUi()
    }

    /** One shared slider dialog; the caller re-reads the value and rebuilds. */
    private fun slider(title: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val theme = Look.theme(this)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
        }
        val label = TextView(this).apply {
            text = value.toString()
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(label)
        box.addView(bar)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = (min + progress).toString()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                onChange(min + (sb?.progress ?: 0))
            }
        })
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ theme picker
    private fun showThemeDialog() {
        val theme = Look.theme(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }

        list.addView(
            themeRow(
                name = getString(R.string.appearance_theme_make),
                hint = getString(R.string.appearance_theme_make_hint),
                swatchBg = theme.bg,
                swatchAccent = theme.accent
            ) {
                showCustomThemeDialog()
            }
        )

        for (t in Look.ALL_THEMES) {
            list.addView(
                themeRow(
                    name = t.name,
                    hint = getString(
                        if (t.light) R.string.appearance_light else R.string.appearance_dark
                    ) + if (t.id == theme.id) "  ·  ${getString(R.string.appearance_current)}" else "",
                    swatchBg = t.bg,
                    swatchAccent = t.accent
                ) {
                    Look.setThemeId(this, t.id)
                    buildUi()
                }
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.appearance_row_theme)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeRow(
        name: String,
        hint: String,
        swatchBg: Int,
        swatchAccent: Int,
        onClick: () -> Unit
    ): View {
        val theme = Look.theme(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(swatchBg)
                setStroke(dp(2), swatchAccent)
            }
        }
        row.addView(swatch, LinearLayout.LayoutParams(dp(34), dp(34)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(
            TextView(this).apply {
                text = name
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.MONOSPACE
            }
        )
        texts.addView(
            TextView(this).apply {
                text = hint
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
            }
        )
        row.addView(texts)
        return row
    }

    // ----------------------------------------------------------- custom theme
    private fun showCustomThemeDialog() {
        val current = Look.customTheme(this)
        var bg = current.bg
        var text = current.textPrimary
        var text2 = current.textSecondary
        var accent = current.accent

        fun open() {
            val dialogTheme = Look.theme(this)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
            }
            box.addView(
                TextView(this).apply {
                    text = getString(R.string.appearance_custom_hint)
                    setTextColor(dialogTheme.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, 0, 0, dp(8))
                }
            )

            fun colourRow(title: String, value: Int, apply: (Int) -> Unit) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                    isClickable = true
                }
                row.addView(
                    TextView(this).apply {
                        text = title
                        setTextColor(dialogTheme.textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        typeface = Typeface.MONOSPACE
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                row.addView(swatch(value, dialogTheme.textSecondary), LinearLayout.LayoutParams(dp(30), dp(30)))
                row.setOnClickListener { pickColor(title, value, apply) }
                box.addView(row)
            }

            colourRow(getString(R.string.appearance_custom_bg), bg) { bg = it; open() }
            colourRow(getString(R.string.appearance_custom_text), text) { text = it; open() }
            colourRow(getString(R.string.appearance_custom_text2), text2) { text2 = it; open() }
            colourRow(getString(R.string.appearance_custom_accent), accent) { accent = it; open() }

            AlertDialog.Builder(this)
                .setTitle(R.string.appearance_custom_title)
                .setView(box)
                .setPositiveButton(R.string.appearance_save) { _, _ ->
                    Look.setCustomColours(this, bg, text, text2, accent)
                    Look.setThemeId(this, Look.CUSTOM_ID)
                    buildUi()
                    Toast.makeText(this, R.string.appearance_saved, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        open()
    }

    /** A tiny H/S/V picker: three sliders over a live preview. */
    private fun pickColor(title: String, initial: Int, onPick: (Int) -> Unit) {
        val theme = Look.theme(this)
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val preview = View(this)
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val hex = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setTextColor(theme.textSecondary)
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(hex)

        fun refresh() {
            val colour = Color.HSVToColor(hsv)
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(colour)
                setStroke(dp(1), theme.textSecondary)
            }
            hex.text = String.format("#%06X", 0xFFFFFF and colour)
        }

        fun addBar(index: Int, max: Int, value: Float, scale: Float) {
            val bar = SeekBar(this).apply {
                this.max = max
                progress = (value / scale).toInt().coerceIn(0, max)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    hsv[index] = p * scale
                    refresh()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            box.addView(bar)
        }

        addBar(0, 360, hsv[0], 1f)
        addBar(1, 100, hsv[1], 0.01f)
        addBar(2, 100, hsv[2], 0.01f)
        refresh()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPick(Color.HSVToColor(hsv)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------- labels
    private fun styleLabel(mode: DisplayMode): Int = when (mode) {
        DisplayMode.NAME -> R.string.appearance_style_name
        DisplayMode.ICON -> R.string.appearance_style_icon
        DisplayMode.ICON_NAME -> R.string.appearance_style_both
    }

    private fun shapeLabel(shape: IconShape): Int = when (shape) {
        IconShape.ORIGINAL -> R.string.appearance_shape_original
        IconShape.CIRCLE -> R.string.appearance_shape_circle
        IconShape.ROUNDED -> R.string.appearance_shape_rounded
        IconShape.SQUARE -> R.string.appearance_shape_square
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
