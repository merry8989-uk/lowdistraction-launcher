package com.lowdistraction.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.lowdistraction.launcher.bubble.BubblePrefs
import com.lowdistraction.launcher.bubble.FloatingBubbleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gathers everything worth knowing about this install and renders it as a
 * report. Nothing here touches the network: every value is read from public
 * local APIs, and the log is the app's own file.
 *
 * Four shapes are offered, because they are good at different things:
 *   .txt   — read it in a mail or a notes app
 *   .md    — read it on GitHub / paste it into an issue
 *   .json  — feed it to a tool
 *   .csv   — open the log as a table in Excel or Google Sheets
 */
object DiagnosticsReport {

    /** Where the "Email" button sends the report. */
    const val EMAIL = "br19shc39@gmail.com"

    data class Row(val key: String, val value: String)

    data class Section(val title: String, val rows: List<Row>)

    class Report(
        val generatedAt: Long,
        val appName: String,
        val version: String,
        val versionCode: Long,
        val enabled: Boolean,
        val sections: List<Section>,
        val log: List<Diag.Entry>
    ) {
        val logBytes: Long get() = log.sumOf { it.message.length.toLong() }
        val crashCount: Int get() = log.count { it.level == "FATAL" }
    }

    // ------------------------------------------------------------- collecting
    fun collect(context: Context): Report {
        val sections = ArrayList<Section>()
        sections.add(Section("App", appRows(context)))
        sections.add(Section("Device", deviceRows(context)))
        sections.add(Section("Launcher settings", settingsRows(context)))
        sections.add(Section("Permissions", permissionRows(context)))
        return Report(
            generatedAt = System.currentTimeMillis(),
            appName = "Low Distraction",
            version = Diag.versionName(context),
            versionCode = Diag.versionCode(context),
            enabled = Diag.isEnabled(context),
            sections = sections,
            log = Diag.entries(context)
        )
    }

    private fun appRows(c: Context): List<Row> = buildList {
        add(Row("Name", "Low Distraction"))
        add(Row("Package", c.packageName))
        add(Row("Version", Diag.versionName(c) + " (" + Diag.versionCode(c) + ")"))
        add(Row("Build", if (isDebuggable(c)) "debug" else "release"))
        add(Row("Diagnostics", if (Diag.isEnabled(c)) "on" else "off"))
        add(Row("Log size", humanSize(Diag.sizeBytes(c)) + ", " + Diag.entries(c).size + " records"))
        add(Row("Generated", isoLocal(System.currentTimeMillis())))
        add(Row("Locale", Locale.getDefault().toString()))
        add(Row("Timezone", TimeZone.getDefault().id))
        add(Row("Screen", screenSummary(c)))
    }

    private fun deviceRows(c: Context): List<Row> {
        val out = ArrayList<Row>()
        // Reuse the Dev.Check collectors so the report matches what the device
        // window shows. Anything a tab cannot read is already caught there.
        for (tab in listOf("Hardware", "System", "Battery", "Network")) {
            out.add(Row(tab.uppercase(Locale.US), ""))
            for (r in DeviceInfo.rows(c, tab)) {
                out.add(Row(r.label, r.value))
            }
        }
        return out
    }

    private fun settingsRows(c: Context): List<Row> {
        val theme = Look.theme(c)
        val out = ArrayList<Row>()
        out.add(Row("Theme", theme.name + " (" + Look.themeId(c) + ")"))
        out.add(Row("Display mode", Look.displayMode(c).name))
        out.add(Row("Icon size", Look.iconSize(c).toString()))
        out.add(Row("Icon shape", Look.iconShape(c).name))
        out.add(Row("Icon outline", Look.iconOutline(c).toString()))
        out.add(Row("Text size", Look.nameSize(c).toString()))
        out.add(Row("Text bold", Look.nameBold(c).toString()))
        out.add(Row("Text spacing", Look.nameSpacing(c).toString()))
        out.add(Row("Drifting glow", Look.glowEnabled(c).toString()))
        out.add(
            Row(
                "Pinned grid",
                Look.pinnedRows(c).toString() + " x " + Look.pinnedCols(c) +
                    " (" + Look.pinnedCapacity(c) + " slots, " + PinnedApps(c).all().size + " pinned)"
            )
        )
        out.add(Row("Hidden apps", HiddenApps(c).all().size.toString()))
        out.add(Row("Sort mode", SortPrefs.mode(c).name))
        out.add(Row("Sort orders on", SortPrefs.enabledModes(c).size.toString()))
        out.add(Row("Pause before opening", ShortcutPrefs.isLaunchPauseEnabled(c).toString()))
        out.add(Row("Pause length", ShortcutPrefs.pauseSeconds(c).toString() + " s"))
        out.add(Row("Tick sound", ShortcutPrefs.isTickSoundEnabled(c).toString()))
        out.add(Row("Wheel sound", WheelSound.selected(c).name))
        out.add(Row("Bubble running", FloatingBubbleService.running.toString()))
        out.add(Row("Bubble ring apps", BubblePrefs(c).pinned().size.toString() + " of " + BubblePrefs.MAX))
        out.add(Row("Hide over payment apps", SensitiveApps.isEnabled(c).toString()))
        out.add(Row("Also pause accessibility", SensitiveApps.isStrict(c).toString()))
        out.add(Row("Auto-detect by name", SensitiveApps.isAutoName(c).toString()))
        out.add(Row("Auto-detect tap-to-pay", SensitiveApps.isAutoHce(c).toString()))
        out.add(Row("Added sensitive apps", SensitiveApps.userPackages(c).size.toString()))
        out.add(Row("Exempted sensitive apps", SensitiveApps.excludedPackages(c).size.toString()))
        out.add(Row("Search: settings", SearchSources.settings(c).toString()))
        out.add(Row("Search: contacts", SearchSources.contacts(c).toString()))
        out.add(Row("Search: calendar", SearchSources.calendar(c).toString()))
        out.add(Row("Search: files", SearchSources.files(c).toString()))
        out.add(Row("Search: Dev.Check", SearchSources.devCheck(c).toString()))
        out.add(Row("Guide seen", ShortcutPrefs.isGuideSeen(c).toString()))
        return out
    }

    private fun permissionRows(c: Context): List<Row> = buildList {
        add(Row("Draw over other apps", granted(Settings.canDrawOverlays(c))))
        add(Row("Accessibility service", granted(com.lowdistraction.launcher.bubble.AssistiveTouchService.isReady)))
        add(Row("Contacts", granted(has(c, android.Manifest.permission.READ_CONTACTS))))
        add(Row("Calendar", granted(has(c, android.Manifest.permission.READ_CALENDAR))))
        add(Row("All files access", granted(allFiles(c))))
        add(Row("Usage access", granted(AppSorter.hasUsageAccess(c))))
        add(Row("Do Not Disturb access", granted(dnd(c))))
        add(Row("Modify system settings", granted(Settings.System.canWrite(c))))
        add(Row("Notifications", granted(notifications(c))))
    }

    // ------------------------------------------------------------- rendering
    fun renderText(r: Report): String {
        val sb = StringBuilder()
        sb.append("LOW DISTRACTION — DIAGNOSTICS\n")
        sb.append("Version ").append(r.version).append(" (").append(r.versionCode).append(")\n")
        sb.append("Generated ").append(local(r.generatedAt)).append("\n")
        sb.append("Diagnostics ").append(if (r.enabled) "on" else "off")
        sb.append("  ·  ").append(r.log.size).append(" log records\n")
        sb.append("=".repeat(64)).append("\n\n")

        for (section in r.sections) {
            sb.append("[").append(section.title.uppercase(Locale.US)).append("]\n")
            val width = section.rows.filter { it.value.isNotEmpty() }
                .maxOfOrNull { it.key.length } ?: 0
            for (row in section.rows) {
                if (row.value.isEmpty()) {
                    sb.append("  ").append(row.key).append("\n")
                } else {
                    sb.append("  ").append(row.key.padEnd(width)).append("  ").append(row.value).append("\n")
                }
            }
            sb.append("\n")
        }

        sb.append("[LOG — ").append(r.log.size).append(" records]\n")
        if (r.log.isEmpty()) {
            sb.append("  (nothing recorded yet)\n")
        } else {
            for (e in r.log) {
                sb.append(Diag.stamp(e.time)).append("  ").append(e.level.padEnd(5)).append(" ")
                    .append(e.tag).append(": ").append(e.message).append("\n")
            }
        }
        sb.append("\n")
        sb.append("Note: Low Distraction has no internet permission at all. This\n")
        sb.append("report was written on the device and only leaves it because you\n")
        sb.append("chose to export or send it.\n")
        return sb.toString()
    }

    fun renderMarkdown(r: Report): String {
        val sb = StringBuilder()
        sb.append("# Low Distraction — diagnostics\n\n")
        sb.append("**Version** ").append(r.version).append(" (").append(r.versionCode).append(")  \n")
        sb.append("**Generated** ").append(local(r.generatedAt)).append("  \n")
        sb.append("**Diagnostics** ").append(if (r.enabled) "on" else "off")
        sb.append(" · ").append(r.log.size).append(" log records\n\n")

        for (section in r.sections) {
            sb.append("## ").append(section.title).append("\n\n")
            sb.append("| Key | Value |\n| --- | --- |\n")
            for (row in section.rows) {
                if (row.value.isEmpty()) {
                    sb.append("| **").append(mdCell(row.key)).append("** | |\n")
                } else {
                    sb.append("| ").append(mdCell(row.key)).append(" | ")
                        .append(mdCell(row.value)).append(" |\n")
                }
            }
            sb.append("\n")
        }

        sb.append("## Log\n\n")
        if (r.log.isEmpty()) {
            sb.append("_Nothing recorded yet._\n\n")
        } else {
            sb.append("```text\n")
            for (e in r.log) {
                sb.append(Diag.stamp(e.time)).append("  ").append(e.level.padEnd(5)).append(" ")
                    .append(e.tag).append(": ").append(e.message).append("\n")
            }
            sb.append("```\n\n")
        }
        sb.append("_Low Distraction has no internet permission; this report was written ")
        sb.append("on the device and only leaves it because you chose to export it._\n")
        return sb.toString()
    }

    fun renderJson(r: Report): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"app\": ").append(json(r.appName)).append(",\n")
        sb.append("  \"package\": ").append(json(packageOf(r))).append(",\n")
        sb.append("  \"versionName\": ").append(json(r.version)).append(",\n")
        sb.append("  \"versionCode\": ").append(r.versionCode).append(",\n")
        sb.append("  \"generatedAt\": ").append(json(isoLocal(r.generatedAt))).append(",\n")
        sb.append("  \"generatedAtMillis\": ").append(r.generatedAt).append(",\n")
        sb.append("  \"diagnosticsEnabled\": ").append(r.enabled).append(",\n")
        sb.append("  \"internetPermission\": false,\n")
        sb.append("  \"sections\": [\n")
        for ((si, section) in r.sections.withIndex()) {
            sb.append("    {\n      \"title\": ").append(json(section.title)).append(",\n")
            sb.append("      \"rows\": [\n")
            for ((ri, row) in section.rows.withIndex()) {
                sb.append("        { \"key\": ").append(json(row.key))
                    .append(", \"value\": ").append(json(row.value)).append(" }")
                sb.append(if (ri == section.rows.size - 1) "\n" else ",\n")
            }
            sb.append("      ]\n    }").append(if (si == r.sections.size - 1) "\n" else ",\n")
        }
        sb.append("  ],\n")
        sb.append("  \"log\": {\n")
        sb.append("    \"count\": ").append(r.log.size).append(",\n")
        sb.append("    \"crashes\": ").append(r.crashCount).append(",\n")
        sb.append("    \"entries\": [\n")
        for ((i, e) in r.log.withIndex()) {
            sb.append("      { \"time\": ").append(e.time)
                .append(", \"timeText\": ").append(json(Diag.stamp(e.time)))
                .append(", \"level\": ").append(json(e.level))
                .append(", \"tag\": ").append(json(e.tag))
                .append(", \"message\": ").append(json(e.message)).append(" }")
            sb.append(if (i == r.log.size - 1) "\n" else ",\n")
        }
        sb.append("    ]\n  }\n}\n")
        return sb.toString()
    }

    /** The log as a table — opens straight into Excel or Google Sheets. */
    fun renderCsv(r: Report): String {
        val sb = StringBuilder()
        sb.append("time,epochMillis,level,tag,message\n")
        for (e in r.log) {
            sb.append(csv(Diag.stamp(e.time))).append(',')
                .append(e.time).append(',')
                .append(csv(e.level)).append(',')
                .append(csv(e.tag)).append(',')
                .append(csv(e.message)).append('\n')
        }
        return sb.toString()
    }

    fun fileName(extension: String): String {
        val s = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "lowdistraction-diagnostics-$s.$extension"
    }

    // --------------------------------------------------------------- helpers
    private fun packageOf(r: Report): String =
        r.sections.firstOrNull { it.title == "App" }
            ?.rows?.firstOrNull { it.key == "Package" }?.value ?: ""

    private fun local(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(time))

    private fun isoLocal(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(time))

    private fun granted(ok: Boolean) = if (ok) "granted" else "not granted"

    private fun has(c: Context, permission: String): Boolean =
        runCatching { c.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun allFiles(c: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            has(c, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.getOrDefault(false)

    private fun dnd(c: Context): Boolean = runCatching {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    private fun notifications(c: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            has(c, "android.permission.POST_NOTIFICATIONS")
        } else {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.areNotificationsEnabled()
        }
    }.getOrDefault(false)

    private fun isDebuggable(c: Context): Boolean =
        (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun screenSummary(c: Context): String = runCatching {
        val dm = c.resources.displayMetrics
        val w = (dm.widthPixels / dm.density).toInt()
        val h = (dm.heightPixels / dm.density).toInt()
        w.toString() + " x " + h + " dp, density " + String.format(Locale.US, "%.2f", dm.density) +
            ", " + dm.widthPixels + " x " + dm.heightPixels + " px"
    }.getOrDefault("—")

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> bytes.toString() + " B"
    }

    private fun mdCell(s: String): String =
        s.replace("|", "\\|").replace("\n", " ").ifEmpty { "—" }

    private fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    private fun json(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format(Locale.US, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
