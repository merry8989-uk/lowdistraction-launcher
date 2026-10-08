package com.lowdistraction.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * The Dev.Check window: a full-screen, tabbed read-out of the device —
 * Dashboard, Hardware, System, Battery, Network, Apps and Sensors.
 */
class DevCheckActivity : Activity() {

    private lateinit var adapter: DevCheckAdapter
    private lateinit var tabs: LinearLayout
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devcheck)

        tabs = findViewById(R.id.devcheckTabs)
        list = findViewById(R.id.devcheckList)
        adapter = DevCheckAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<TextView>(R.id.devcheckBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.devcheckSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        buildTabs()

        val wanted = intent?.getStringExtra(EXTRA_TAB)
        val index = DeviceInfo.tabs.indexOfFirst { it.equals(wanted, ignoreCase = true) }
        selectTab(if (index >= 0) index else 0)
    }

    private fun buildTabs() {
        val density = resources.displayMetrics.density
        DeviceInfo.tabs.forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 14f
                isClickable = true
                setPadding((12 * density).roundToInt(), (8 * density).roundToInt(),
                    (12 * density).roundToInt(), (8 * density).roundToInt())
                setOnClickListener { selectTab(index) }
            }
            tabs.addView(tab)
        }
    }

    private fun selectTab(index: Int) {
        for (i in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as TextView
            val selected = i == index
            tab.setTextColor(if (selected) 0xFF9AE6B4.toInt() else 0xFF8A8A8E.toInt())
            tab.background = if (selected) chip() else null
        }
        adapter.submit(DeviceInfo.rows(this, DeviceInfo.tabs[index]))
        list.scrollToPosition(0)
    }

    private fun chip(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 12f * resources.displayMetrics.density
        setColor(0x1A9AE6B4)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
