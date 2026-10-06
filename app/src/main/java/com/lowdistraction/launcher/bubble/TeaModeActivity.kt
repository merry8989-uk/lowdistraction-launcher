package com.lowdistraction.launcher.bubble

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lowdistraction.launcher.R

/**
 * "Tea mode": a 5 minute break screen. It fills the display, keeps the screen
 * on and swallows Back. A determined user can still reach the Home button —
 * Android does not allow a normal app to block that — but there is nothing to
 * do here, so hold the hint for 3 seconds to end early.
 */
class TeaModeActivity : Activity() {

    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0B0B0C.toInt())
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = getString(R.string.tea_mode_title)
            setTextColor(0xFFEDEDED.toInt())
            textSize = 34f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.tea_mode_subtitle)
            setTextColor(0xFF8A8A8E.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val clock = TextView(this).apply {
            setTextColor(0xFF9AE6B4.toInt())
            textSize = 46f
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = getString(R.string.tea_mode_hold_to_exit)
            setTextColor(0xFF8A8A8E.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(clock)
        root.addView(hint)
        setContentView(root)

        hint.setOnLongClickListener { finish(); true }

        timer = object : CountDownTimer(FIVE_MINUTES, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val total = millisUntilFinished / 1000
                clock.text = getString(
                    R.string.tea_mode_remaining,
                    "%d:%02d".format(total / 60, total % 60)
                )
            }

            override fun onFinish() {
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    @Deprecated("Tea mode blocks leaving via Back")
    override fun onBackPressed() {
        // Swallow: no escaping with Back.
    }

    private companion object {
        const val FIVE_MINUTES = 5 * 60 * 1000L
    }
}
