package com.lowdistraction.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings

/**
 * "Dev.Check" — a small set of device-information shortcuts. When the user
 * turns it on in Settings, these show up as results in the right-hand search
 * bar; the little Dev.Check badge above that bar jumps to the setting.
 *
 * Everything here is read locally from public APIs. Nothing leaves the device
 * (the app has no INTERNET permission at all).
 */
object DevCheck {

    const val ID_DASHBOARD = "dashboard"
    const val ID_SYSTEM = "system"
    const val ID_SENSORS = "sensors"
    const val ID_CAMERA = "camera"

    fun entries(): List<QuickEntry> = listOf(
        QuickEntry("Dev.Check: Dashboard", "Overview", null, ID_DASHBOARD),
        QuickEntry("Dev.Check: System", "OS, build & kernel", null, ID_SYSTEM),
        QuickEntry("Dev.Check: Sensors", "All sensors", null, ID_SENSORS),
        QuickEntry("Dev.Check: Camera", "Camera specs", null, ID_CAMERA),
        QuickEntry("Dev.Check: Hardware", "About phone", Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        QuickEntry("Dev.Check: Battery", "Battery settings", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
        QuickEntry("Dev.Check: Network", "Wi-Fi & mobile", Intent(Settings.ACTION_WIRELESS_SETTINGS)),
        QuickEntry("Dev.Check: Display", "Display settings", Intent(Settings.ACTION_DISPLAY_SETTINGS)),
        QuickEntry("Dev.Check: Storage", "Storage settings", Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
        QuickEntry("Dev.Check: Apps", "Manage apps", Intent(Settings.ACTION_APPLICATION_SETTINGS))
    )

    /** Keywords used to match a typed query against the entries. */
    fun matches(entry: QuickEntry, query: String): Boolean {
        val q = query.lowercase()
        if (entry.label.lowercase().contains(q)) return true
        return when (entry.id) {
            ID_DASHBOARD -> "dashboard overview summary".contains(q)
            ID_SYSTEM -> "system build kernel os version".contains(q)
            ID_SENSORS -> "sensor sensors".contains(q)
            ID_CAMERA -> "camera lens".contains(q)
            else -> false
        }
    }

    /** Which Dev.Check tab a shortcut should open. */
    fun tabFor(id: String): String = when (id) {
        ID_SYSTEM -> "System"
        ID_SENSORS -> "Sensors"
        ID_CAMERA -> "Camera"
        else -> "Dashboard"
    }

    fun title(id: String): String = when (id) {
        ID_DASHBOARD -> "Dev.Check · Dashboard"
        ID_SYSTEM -> "Dev.Check · System"
        ID_SENSORS -> "Dev.Check · Sensors"
        ID_CAMERA -> "Dev.Check · Camera"
        else -> "Dev.Check"
    }

    fun body(context: Context, id: String): String = when (id) {
        ID_DASHBOARD -> dashboard(context)
        ID_SYSTEM -> system()
        ID_SENSORS -> sensors(context)
        ID_CAMERA -> camera(context)
        else -> ""
    }

    // ------------------------------------------------------------------ info
    private fun dashboard(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        sb.appendLine("Battery: ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        sb.appendLine("RAM: ${gb(mem.availMem)} free of ${gb(mem.totalMem)}")

        val stat = StatFs(Environment.getDataDirectory().path)
        sb.appendLine("Storage: ${gb(stat.availableBytes)} free of ${gb(stat.totalBytes)}")
        return sb.toString().trim()
    }

    private fun system(): String = buildString {
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Product: ${Build.PRODUCT}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Kernel: ${System.getProperty("os.version")}")
        appendLine("Build: ${Build.ID}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
    }.trim()

    private fun sensors(context: Context): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        if (list.isEmpty()) return "No sensors reported"
        return list.joinToString("\n") { s ->
            "${s.name}\n    ${s.vendor} · ${s.power} mA"
        }
    }

    private fun camera(context: Context): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        runCatching {
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val max = map?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width * it.height }
                sb.appendLine(
                    "Camera $id · $facing · flash: $flash" +
                        (max?.let { " · max ${it.width}×${it.height}" } ?: "")
                )
            }
        }
        return sb.toString().trim().ifEmpty { "No cameras reported" }
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)
}
