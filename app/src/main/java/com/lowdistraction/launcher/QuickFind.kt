package com.lowdistraction.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One result row in the second (system) search bar. */
data class QuickEntry(
    val label: String,
    val hint: String,
    val intent: Intent? = null,
    /** When set, the action is handled inside the app (see DevCheck). */
    val id: String? = null
)

/**
 * Backs the second search bar: it looks through device settings shortcuts,
 * contacts and calendar events, and falls back to the device's own search.
 */
object QuickFind {

    private class Target(val label: String, val keys: String, val action: String)

    private val targets = listOf(
        Target("Wi-Fi", "wifi wireless internet network", Settings.ACTION_WIFI_SETTINGS),
        Target("Bluetooth", "bluetooth devices", Settings.ACTION_BLUETOOTH_SETTINGS),
        Target("Mobile network", "mobile network sim data roaming", Settings.ACTION_DATA_ROAMING_SETTINGS),
        Target("Hotspot & tethering", "hotspot tethering wireless", Settings.ACTION_WIRELESS_SETTINGS),
        Target("Display", "display screen brightness", Settings.ACTION_DISPLAY_SETTINGS),
        Target("Sound", "sound volume ringtone", Settings.ACTION_SOUND_SETTINGS),
        Target("Battery", "battery power saver", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Target("Storage", "storage memory space", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Target("Apps", "apps applications manage", Settings.ACTION_APPLICATION_SETTINGS),
        Target("Location", "location gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Target("Security", "security lock screen", Settings.ACTION_SECURITY_SETTINGS),
        Target("Privacy", "privacy permissions", Settings.ACTION_PRIVACY_SETTINGS),
        Target("Accessibility", "accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Target("Notifications", "notifications alerts", "android.settings.NOTIFICATION_SETTINGS"),
        Target("Language & input", "language keyboard input", Settings.ACTION_LOCALE_SETTINGS),
        Target("Date & time", "date time clock", Settings.ACTION_DATE_SETTINGS),
        Target("About phone", "about phone device info", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Target("Home app", "home launcher default", Settings.ACTION_HOME_SETTINGS),
        Target("All settings", "settings all", Settings.ACTION_SETTINGS)
    )

    fun search(context: Context, query: String, includeDevCheck: Boolean = false): List<QuickEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val out = ArrayList<QuickEntry>()
        out += contactEntries(context, q)
        out += calendarEntries(context, q)
        out += settingsEntries(q)
        if (includeDevCheck) {
            out += DevCheck.entries().filter { DevCheck.matches(it, q) }
        }

        if (out.isEmpty()) {
            out.add(
                QuickEntry(
                    "Search “$q” on device",
                    "Hand off to the device search",
                    Intent(Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, q)
                )
            )
            out.add(QuickEntry("Open Settings", "All device settings", Intent(Settings.ACTION_SETTINGS)))
        }
        return out
    }

    private fun settingsEntries(query: String): List<QuickEntry> {
        val q = query.lowercase()
        return targets
            .filter { it.label.lowercase().contains(q) || it.keys.contains(q) }
            .map { QuickEntry(it.label, "Settings", Intent(it.action)) }
    }

    private fun contactEntries(context: Context, query: String): List<QuickEntry> {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<QuickEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                null
            )?.use { c ->
                while (c.moveToNext() && out.size < 8) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: ""
                    val id = c.getString(2) ?: continue
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id.toLong())
                    )
                    out.add(QuickEntry(name, number, intent))
                }
            }
        }
        return out
    }

    private fun calendarEntries(context: Context, query: String): List<QuickEntry> {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) return emptyList()
        val out = ArrayList<QuickEntry>()
        val now = System.currentTimeMillis()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART
        )
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DTSTART} >= ?",
                arrayOf("%$query%", now.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 8) {
                    val id = c.getLong(0)
                    val title = c.getString(1) ?: continue
                    val start = c.getLong(2)
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
                    )
                    out.add(QuickEntry(title, formatWhen(start), intent))
                }
            }
        }
        return out
    }

    private fun formatWhen(millis: Long): String =
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

    private fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
