package com.lowdistraction.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.lowdistraction.launcher.bubble.AssistiveTouchService
import com.lowdistraction.launcher.bubble.FloatingBubbleService
import kotlin.math.cos
import kotlin.math.sin

/**
 * The first-run guide. Walks the user through where everything lives: how to
 * open Settings, the assistive wheel (with a sound test), themes, the second
 * search box, taking permissions back, and setting the app as the launcher.
 */
class GuideActivity : Activity() {

    private var step = 0
    private lateinit var theme: Theme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Look.theme(this)
        render()
    }

    private val stepCount = 9

    private fun render() {
        theme = Look.theme(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(44), dp(24), dp(32))
        }

        // ------------------------------------------------------------ top row
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            TextView(this).apply {
                setText(getString(R.string.guide_step, step + 1, stepCount))
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(
            TextView(this).apply {
                setText(R.string.guide_skip)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.MONOSPACE
                isClickable = true
                setOnClickListener { finishGuide() }
            }
        )
        col.addView(top)

        // -------------------------------------------------------- step content
        val content = when (step) {
            0 -> step1()
            1 -> step2()
            2 -> step3()
            3 -> step4()
            4 -> step5()
            5 -> step6()
            6 -> step7()
            7 -> step8()
            else -> step9()
        }
        col.addView(content)

        // -------------------------------------------------------------- nav
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(26), 0, 0)
        }
        if (step > 0) {
            nav.addView(
                button(getString(R.string.guide_back), false) {
                    step--
                    render()
                }
            )
        }
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(
            button(
                if (step == stepCount - 1) getString(R.string.guide_done)
                else getString(R.string.guide_next),
                true
            ) {
                if (step == stepCount - 1) {
                    finishGuide()
                } else {
                    step++
                    render()
                }
            }
        )
        col.addView(nav)

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(theme.bg)
                addView(col)
            }
        )
        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = theme.light
        controller.isAppearanceLightNavigationBars = theme.light
    }

    // ------------------------------------------------------------- the steps
    private fun step1(): View = page(
        R.string.guide_s1_title,
        R.string.guide_s1_body,
        null
    )

    private fun step2(): View = page(
        R.string.guide_s2_title,
        R.string.guide_s2_body,
        longPressArt()
    )

    private fun step3(): View = page(
        R.string.guide_s3_title,
        R.string.guide_s3_body,
        miniWheel(),
        listOf(
            getString(R.string.guide_test_chime) to { Chime.play(this) },
            getString(R.string.guide_enable_wheel) to { enableBubble() }
        )
    )

    private fun step4(): View = page(
        R.string.guide_s4_title,
        R.string.guide_s4_body,
        themeSwatches(),
        listOf(
            getString(R.string.guide_open_appearance) to {
                startActivity(Intent(this, AppearanceActivity::class.java))
            }
        )
    )

    private fun step5(): View = page(
        R.string.guide_s5_title,
        R.string.guide_s5_body,
        searchBoxArt(),
        listOf(
            getString(R.string.guide_open_sources) to {
                startActivity(Intent(this, SearchSourcesActivity::class.java))
            }
        )
    )

    private fun step6(): View = page(
        R.string.guide_s6_title,
        R.string.guide_s6_body,
        null,
        listOf(
            getString(R.string.guide_open_sources) to {
                startActivity(Intent(this, SearchSourcesActivity::class.java))
            }
        )
    )

    private fun step7(): View = page(
        R.string.guide_s7_title,
        R.string.guide_s7_body,
        null,
        listOf(
            getString(R.string.guide_open_sources) to {
                startActivity(Intent(this, SearchSourcesActivity::class.java))
            }
        )
    )

    private fun step8(): View = page(
        R.string.guide_s8_title,
        R.string.guide_s8_body,
        null,
        listOf(
            getString(R.string.guide_open_launcher) to {
                if (!HomeRole.openHomeSettings(this)) HomeRole.request(this)
            }
        )
    )

    private fun step9(): View = page(
        R.string.guide_s9_title,
        R.string.guide_s9_body,
        null,
        listOf(
            getString(R.string.guide_open_settings) to {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
    )

    /** Title + optional artwork + body + optional action buttons. */
    private fun page(
        titleRes: Int,
        bodyRes: Int,
        art: View?,
        actions: List<Pair<String, () -> Unit>>? = null
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            TextView(this).apply {
                setText(titleRes)
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(22), 0, 0)
            }
        )
        if (art != null) {
            box.addView(
                art,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(18) }
            )
        }
        box.addView(
            TextView(this).apply {
                setText(bodyRes)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(16), 0, 0)
            }
        )
        actions?.forEach { (label, action) ->
            box.addView(
                button(label, false, action),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            )
        }
        return box
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            setText(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                if (filled) {
                    setColor(theme.accent)
                    setTextColor(Look.blend(theme.bg, theme.accent, 0.05f))
                } else {
                    setColor(theme.surface)
                    setStroke(dp(1), Look.blend(theme.surface, theme.accent, 0.6f))
                    setTextColor(theme.accent)
                }
            }
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ artwork
    private fun longPressArt(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(theme.surface)
                setStroke(dp(1), Look.blend(theme.surface, theme.textSecondary, 0.4f))
            }
        }
        box.addView(
            TextView(this).apply {
                setText(R.string.guide_hold)
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            }
        )
        box.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.accent)
                }
            },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(12) }
        )
        return box
    }

    private fun miniWheel(): View {
        val size = dp(180)
        val frame = FrameLayout(this)
        val ring = dp(62).toFloat()
        val dot = dp(32)
        val centre = size / 2f
        for (i in 0 until 6) {
            val angle = Math.toRadians((i * 60).toDouble())
            val x = (centre + ring * cos(angle)).toInt()
            val y = (centre + ring * sin(angle)).toInt()
            frame.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(theme.surface)
                        setStroke(dp(1), theme.accent)
                    }
                },
                FrameLayout.LayoutParams(dot, dot).apply {
                    leftMargin = x - dot / 2
                    topMargin = y - dot / 2
                }
            )
        }
        frame.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.accent)
                }
            },
            FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                leftMargin = (centre - dp(10)).toInt()
                topMargin = (centre - dp(10)).toInt()
            }
        )
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        holder.addView(frame, LinearLayout.LayoutParams(size, size))
        return holder
    }

    private fun themeSwatches(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val picks = listOf(0, 6, 12, 22, 32, 44, 52)
        for (i in picks) {
            val t = Look.ALL_THEMES[i % Look.ALL_THEMES.size]
            row.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(t.bg)
                        setStroke(dp(2), t.accent)
                    }
                },
                LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) }
            )
        }
        return row
    }

    private fun searchBoxArt(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun bar(faded: Boolean): View = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (faded) Look.blend(theme.bg, theme.surface, 0.5f) else theme.surface)
                setStroke(dp(1), Look.blend(theme.surface, theme.textSecondary, 0.5f))
            }
        }
        row.addView(bar(false), LinearLayout.LayoutParams(0, dp(42), 1f))
        row.addView(
            bar(true),
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) }
        )
        return row
    }

    // ------------------------------------------------------------- actions
    private fun enableBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.bubble_need_overlay, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (!AssistiveTouchService.isReady) {
            Toast.makeText(this, R.string.bubble_need_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        FloatingBubbleService.start(this)
    }

    private fun finishGuide() {
        ShortcutPrefs.setGuideSeen(this, true)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
