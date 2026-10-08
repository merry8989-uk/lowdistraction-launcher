package com.lowdistraction.launcher

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import android.view.WindowManager
import com.lowdistraction.launcher.bubble.BubblePrefs
import java.io.File
import java.net.NetworkInterface
import java.util.Locale

/**
 * Collects device information from public APIs for the Dev.Check window.
 * Everything is read locally; the app has no network access at all.
 */
object DeviceInfo {

    val tabs = listOf("Dashboard", "Hardware", "System", "Battery", "Network", "Apps", "Camera", "Sensors")

    data class Row(val header: Boolean, val label: String, val value: String = "")

    fun rows(context: Context, tab: String): List<Row> = when (tab) {
        "Hardware" -> hardware(context)
        "System" -> system(context)
        "Battery" -> battery(context)
        "Network" -> network(context)
        "Apps" -> apps(context)
        "Camera" -> camera(context)
        "Sensors" -> sensors(context)
        else -> dashboard(context)
    }

    // ------------------------------------------------------------------ tabs
    private fun dashboard(context: Context): List<Row> = buildList {
        add(Row(true, "Device"))
        add(Row(false, "Model", "${Build.MANUFACTURER} ${Build.MODEL}"))
        add(Row(false, "Brand", Build.BRAND))
        add(Row(false, "Device", Build.DEVICE))
        add(Row(false, "Product", Build.PRODUCT))

        add(Row(true, "Software"))
        add(Row(false, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        add(Row(false, "Build", Build.ID))
        add(Row(false, "Security patch", Build.VERSION.SECURITY_PATCH))

        add(Row(true, "Processor"))
        add(Row(false, "Cores", Runtime.getRuntime().availableProcessors().toString()))
        add(Row(false, "ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "—"))

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        add(Row(true, "Memory"))
        add(Row(false, "Total RAM", gb(mem.totalMem)))
        add(Row(false, "Available RAM", gb(mem.availMem)))

        val stat = StatFs(Environment.getDataDirectory().path)
        add(Row(true, "Storage"))
        add(Row(false, "Total", gb(stat.totalBytes)))
        add(Row(false, "Free", gb(stat.availableBytes)))

        add(Row(true, "Battery"))
        add(Row(false, "Level", batteryLevel(context)))
    }

    private fun hardware(context: Context): List<Row> = buildList {
        add(Row(true, "Processor"))
        add(Row(false, "Cores", Runtime.getRuntime().availableProcessors().toString()))
        add(Row(false, "Hardware", Build.HARDWARE))
        add(Row(false, "Board", Build.BOARD))
        add(Row(false, "Architecture", System.getProperty("os.arch") ?: "—"))
        add(Row(false, "Supported ABIs", Build.SUPPORTED_ABIS.joinToString(" ")))
        coreFrequencies().forEach { (i, range) -> add(Row(false, "Core $i", range)) }

        add(Row(true, "Graphics"))
        val gl = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.deviceConfigurationInfo.glEsVersion
        }.getOrNull()
        add(Row(false, "OpenGL ES", gl ?: "—"))

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        add(Row(true, "Memory"))
        add(Row(false, "Total RAM", gb(mem.totalMem)))
        add(Row(false, "Available RAM", gb(mem.availMem)))
        add(Row(false, "Low-memory threshold", gb(mem.threshold)))

        val stat = StatFs(Environment.getDataDirectory().path)
        add(Row(true, "Storage"))
        add(Row(false, "Total", gb(stat.totalBytes)))
        add(Row(false, "Free", gb(stat.availableBytes)))
        add(Row(false, "Block size", "${stat.blockSizeLong} B"))

        val dm = context.resources.displayMetrics
        @Suppress("DEPRECATION")
        val refresh = runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.refreshRate
        }.getOrDefault(0f)
        add(Row(true, "Display"))
        add(Row(false, "Resolution", "${dm.widthPixels} × ${dm.heightPixels} px"))
        add(Row(false, "Density", "${dm.densityDpi} dpi"))
        add(Row(false, "Refresh rate", if (refresh > 0f) "%.1f Hz".format(refresh) else "—"))

        val bt = runCatching {
            val a = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            "${a?.name ?: "—"} · ${if (a?.isEnabled == true) "on" else "off"}"
        }.getOrDefault("—")
        add(Row(true, "Bluetooth"))
        add(Row(false, "Adapter", bt))
    }

    private fun system(context: Context): List<Row> = buildList {
        add(Row(true, "Device"))
        add(Row(false, "Manufacturer", Build.MANUFACTURER))
        add(Row(false, "Brand", Build.BRAND))
        add(Row(false, "Model", Build.MODEL))
        add(Row(false, "Device", Build.DEVICE))
        add(Row(false, "Bootloader", Build.BOOTLOADER))
        add(Row(false, "Radio", runCatching { Build.getRadioVersion() }.getOrNull() ?: "—"))

        add(Row(true, "Android"))
        add(Row(false, "Release", Build.VERSION.RELEASE))
        add(Row(false, "API level", Build.VERSION.SDK_INT.toString()))
        add(Row(false, "Build ID", Build.ID))
        add(Row(false, "Build type", Build.TYPE))
        add(Row(false, "Tags", Build.TAGS ?: "—"))
        add(Row(false, "Security patch", Build.VERSION.SECURITY_PATCH))
        add(Row(false, "Fingerprint", Build.FINGERPRINT))

        add(Row(true, "Kernel"))
        add(Row(false, "Version", System.getProperty("os.version") ?: "—"))
        add(Row(false, "Java VM", System.getProperty("java.vm.version") ?: "—"))

        add(Row(true, "Integrity"))
        add(Row(false, "Root", if (rootDetected()) "possible (su found)" else "not detected"))
        add(Row(false, "Hidden apps", HiddenApps(context).all().size.toString()))
    }

    private fun battery(context: Context): List<Row> = buildList {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))

        add(Row(true, "Status"))
        add(Row(false, "Level", batteryLevel(context)))
        add(Row(false, "Status", batteryStatus(intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)))
        add(Row(false, "Plugged", plugged(intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)))
        add(Row(false, "Health", health(intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1)))
        add(Row(false, "Present", (intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true).toString()))

        add(Row(true, "Power"))
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        add(Row(false, "Voltage", if (mv > 0) "$mv mV" else "—"))
        val current = prop(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        add(Row(false, "Current now", if (current != Int.MIN_VALUE) "${current / 1000} mA" else "—"))
        val counter = prop(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        add(Row(false, "Charge counter", if (counter != Int.MIN_VALUE) "${counter / 1000} mAh" else "—"))
        val energy = prop(bm, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        add(Row(false, "Energy counter", if (energy != Int.MIN_VALUE) "${energy / 1000} mWh" else "—"))
        if (mv > 0 && current != Int.MIN_VALUE) {
            add(Row(false, "Power", "%.2f W".format(mv / 1000f * current / 1_000_000f)))
        }

        add(Row(true, "Details"))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        add(Row(false, "Temperature", if (temp > 0) "%.1f °C".format(temp / 10f) else "—"))
        add(Row(false, "Technology", intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "—"))
        add(Row(false, "Capacity", "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"))
    }

    private fun network(context: Context): List<Row> = buildList {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        add(Row(true, "Connection"))
        add(Row(false, "Active", when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "other"
        }))
        add(Row(false, "Metered", (cm.isActiveNetworkMetered).toString()))

        val wifi = runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wm.isWifiEnabled) "enabled" else "disabled"
        }.getOrDefault("—")
        add(Row(true, "Wi-Fi"))
        add(Row(false, "State", wifi))

        val tm = runCatching { context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }.getOrNull()
        add(Row(true, "Mobile"))
        add(Row(false, "Operator", runCatching { tm?.networkOperatorName }.getOrNull().orEmpty().ifEmpty { "—" }))
        add(Row(false, "Country", runCatching { tm?.networkCountryIso }.getOrNull().orEmpty().ifEmpty { "—" }))
        add(Row(false, "SIM state", simState(runCatching { tm?.simState }.getOrNull() ?: -1)))
        add(Row(false, "Phone type", phoneType(runCatching { tm?.phoneType }.getOrNull() ?: -1)))

        add(Row(true, "IP addresses"))
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterNot { it.isLoopbackAddress }
                .map { "${it.hostAddress} (${if (it.hostAddress?.contains(':') == true) "IPv6" else "IPv4"})" }
        }.getOrDefault(emptyList())
        if (addresses.isEmpty()) add(Row(false, "Addresses", "—"))
        else addresses.forEach { add(Row(false, "", it)) }
    }

    private fun apps(context: Context): List<Row> = buildList {
        val launchable = AppRepository.loadApps(context)
        val hidden = HiddenApps(context).all()
        val ring = BubblePrefs(context).pinned()
        add(Row(true, "Installed"))
        add(Row(false, "Launchable apps", launchable.size.toString()))
        add(Row(false, "Hidden apps", hidden.size.toString()))
        add(Row(false, "Bubble ring", "${ring.size} / ${BubblePrefs.MAX}"))
        add(Row(true, "Note"))
        add(Row(false, "Manage apps", "Long-press an app on the home screen, or open Settings → Apps"))
    }

    private fun camera(context: Context): List<Row> = buildList {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                add(Row(true, "Camera $id"))
                add(Row(false, "Facing", when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }))
                add(Row(false, "Flash", (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true).toString()))
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                add(Row(false, "Focal length", focal?.joinToString { "%.1f mm".format(it) } ?: "—"))
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val max = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.maxByOrNull { it.width * it.height }
                add(Row(false, "Max JPEG", max?.let { "${it.width} × ${it.height}" } ?: "—"))
            }
        }
        if (isEmpty()) {
            add(Row(true, "Camera"))
            add(Row(false, "Result", "No cameras reported"))
        }
    }

    private fun sensors(context: Context): List<Row> = buildList {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        if (list.isEmpty()) {
            add(Row(true, "Sensors"))
            add(Row(false, "Result", "No sensors reported"))
        }
        list.forEach { s ->
            add(Row(true, s.name))
            add(Row(false, "Type", sensorType(s.type)))
            add(Row(false, "Vendor", s.vendor))
            add(Row(false, "Version", s.version.toString()))
            add(Row(false, "Power", "${s.power} mA"))
            add(Row(false, "Resolution", s.resolution.toString()))
            add(Row(false, "Max range", s.maximumRange.toString()))
        }
    }

    // --------------------------------------------------------------- helpers
    private fun batteryLevel(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
    }

    private fun prop(bm: BatteryManager, id: Int): Int = runCatching { bm.getIntProperty(id) }.getOrDefault(Int.MIN_VALUE)

    private fun coreFrequencies(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (i in 0 until 16) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val min = readLong("$base/cpuinfo_min_freq")
            val max = readLong("$base/cpuinfo_max_freq")
            if (min == null && max == null) break
            out.add(i to "${khz(min)} – ${khz(max)}")
        }
        return out
    }

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    private fun khz(v: Long?): String = if (v == null) "—" else "${v / 1000} MHz"

    private fun rootDetected(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/app/Superuser.apk", "/system/xbin/daemonsu"
    ).any { runCatching { File(it).exists() }.getOrDefault(false) }

    private fun gb(bytes: Long): String = "%.2f GB".format(bytes / 1_000_000_000.0)

    private fun batteryStatus(s: Int) = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "unknown"
    }

    private fun plugged(p: Int) = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "no"
    }

    private fun health(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        else -> "unknown"
    }

    private fun simState(s: Int) = when (s) {
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        else -> "unknown"
    }

    private fun phoneType(t: Int) = when (t) {
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        else -> "none"
    }

    private fun sensorType(t: Int): String = runCatching {
        Sensor::class.java.getField("TYPE_${t}").name
    }.getOrNull()?.removePrefix("TYPE_") ?: t.toString()
}
