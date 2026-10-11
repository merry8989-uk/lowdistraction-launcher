package com.lowdistraction.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Dev.Check window: a full-screen, tabbed read-out of the device —
 * Dashboard, Hardware, System, Battery, Network, Apps, Camera and Sensors.
 *
 * The Sensors tab is live: every sensor the device reports gets a value row that
 * keeps updating while the tab is open, and the listeners are released the
 * moment it is left.
 */
class DevCheckActivity : Activity(), SensorEventListener {

    private lateinit var adapter: DevCheckAdapter
    private lateinit var tabs: LinearLayout
    private lateinit var list: RecyclerView
    private var selectedTab = 0

    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val live = HashMap<String, FloatArray>()
    private val valueRowOf = HashMap<String, Int>()
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { refreshLive() }

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
                setPadding(
                    (12 * density).roundToInt(), (8 * density).roundToInt(),
                    (12 * density).roundToInt(), (8 * density).roundToInt()
                )
                setOnClickListener { selectTab(index) }
            }
            tabs.addView(tab)
        }
    }

    private fun selectTab(index: Int) {
        selectedTab = index
        for (i in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as TextView
            val selected = i == index
            tab.setTextColor(if (selected) 0xFF9AE6B4.toInt() else 0xFF8A8A8E.toInt())
            tab.background = if (selected) chip() else null
        }

        if (DeviceInfo.tabs[index] == SENSORS_TAB) {
            showSensors()
        } else {
            stopListening()
            adapter.submit(DeviceInfo.rows(this, DeviceInfo.tabs[index]))
        }
        list.scrollToPosition(0)
    }

    // ------------------------------------------------------- live sensor read-out
    private fun showSensors() {
        val sensors = DeviceInfo.sensorList(this)
        val rows = ArrayList<DeviceInfo.Row>()
        valueRowOf.clear()
        live.clear()

        if (sensors.isEmpty()) {
            rows.add(DeviceInfo.Row(true, "Sensors"))
            rows.add(DeviceInfo.Row(false, "Result", "No sensors reported"))
        }
        for (sensor in sensors) {
            rows.add(DeviceInfo.Row(true, sensor.name))
            rows.addAll(DeviceInfo.sensorMetaRows(sensor))
            valueRowOf[key(sensor)] = rows.size
            rows.add(DeviceInfo.Row(false, "Value", "waiting…"))
        }

        adapter.submit(rows)
        startListening(sensors)
    }

    private fun startListening(sensors: List<Sensor>) {
        stopListening()
        for (sensor in sensors) {
            runCatching {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
        listening = true
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopListening() {
        if (listening) runCatching { sensorManager.unregisterListener(this) }
        listening = false
        handler.removeCallbacks(tick)
    }

    override fun onSensorChanged(event: SensorEvent) {
        live[key(event.sensor)] = event.values.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Pushes the latest readings into the list a few times a second. */
    private fun refreshLive() {
        for ((id, values) in live) {
            val index = valueRowOf[id] ?: continue
            adapter.updateValue(index, format(values))
        }
        if (listening) handler.postDelayed(tick, TICK_MS)
    }

    private fun format(values: FloatArray): String =
        values.joinToString(", ") { String.format(Locale.US, "%.3f", it) }

    private fun key(sensor: Sensor): String = sensor.name + "#" + sensor.type

    // --------------------------------------------------------------- lifecycle
    override fun onResume() {
        super.onResume()
        if (DeviceInfo.tabs.getOrNull(selectedTab) == SENSORS_TAB) {
            if (valueRowOf.isEmpty()) showSensors()
            else startListening(DeviceInfo.sensorList(this))
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun chip(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 12f * resources.displayMetrics.density
        setColor(0x1A9AE6B4)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        private const val SENSORS_TAB = "Sensors"
        /** How often the live readings are pushed to the screen. */
        private const val TICK_MS = 250L
    }
}
