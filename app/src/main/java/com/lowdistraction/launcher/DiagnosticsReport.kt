package com.lowdistraction.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.lowdistraction.launcher.bubble.BubblePrefs
import com.lowdistraction.launcher.bubble.FloatingBubbleService
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gathers everything worth knowing about this install and renders it as a
 * report. Nothing here touches the network: every value is read from public
 * local APIs, and the log is the app's own file.
 *
 * Four shapes are offered, because they are good at different things:
 *   .txt   — read it in a mail or a notes app
 *   .md    — read it on GitHub / paste it into an issue
 *   .json  — the exact structured records, one object per event
 *   .csv   — one row per event, columns unioned, for Excel or Google Sheets
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
        val schemaVersion: String,
        val sections: List<Section>,
        val events: List<Diag.Event>,
        val rawEvents: List<String>
    ) {
        val crashCount: Int get() = events.count { it.name == "crash" }
        val sessions: Int get() = events.count { it.name == "session_start" }
    }

    // ------------------------------------------------------------- collecting
    fun collect(context: Context): Report {
        val events = Diag.entries(context)
        val sections = ArrayList<Section>()
        sections.add(Section("App", appRows(context)))
        sections.add(Section("Device", deviceRows(context)))
        sections.add(Section("Launcher settings", settingsRows(context)))
        sections.add(Section("Permissions", permissionRows(context)))
        sections.add(Section("Log categories", categoryRows(events)))
        sections.add(Section("Performance", performanceRows(context, events)))
        sections.add(Section("Signals", signalRows(events)))
        sections.add(Section("Log health", healthRows(events)))
        sections.add(Section("Privacy", privacyRows()))
        return Report(
            generatedAt = System.currentTimeMillis(),
            appName = "Low Distraction",
            version = Diag.versionName(context),
            versionCode = Diag.versionCode(context),
            enabled = Diag.isEnabled(context),
            schemaVersion = Diag.SCHEMA_VERSION,
            sections = sections,
            events = events,
            rawEvents = Diag.rawEvents(context)
        )
    }

    private fun appRows(c: Context): List<Row> = buildList {
        add(Row("Name", "Low Distraction"))
        add(Row("Package", c.packageName))
        add(Row("Version", Diag.versionName(c) + " (" + Diag.versionCode(c) + ")"))
        add(Row("Build", if (isDebuggable(c)) "debug" else "release"))
        add(Row("Diagnostics", if (Diag.isEnabled(c)) "on" else "off"))
        add(Row("Log schema", Diag.SCHEMA_VERSION))
        // lineCount just counts rows; parsing every record here would be wasted work.
        add(
            Row(
                "Log size",
                humanSize(Diag.sizeBytes(c)) + ", " + Diag.lineCount(c) + " records"
            )
        )
        add(Row("Session", Diag.sessionId()))
        add(Row("Generated", local(System.currentTimeMillis())))
        add(Row("Timezone", TimeZone.getDefault().id))
        add(Row("Locale", Locale.getDefault().toString()))
        add(Row("Screen", screenSummary(c)))
    }

    private fun deviceRows(c: Context): List<Row> {
        val out = ArrayList<Row>()
        // Reuse the Dev.Check collectors so the report matches the device window.
        for (tab in listOf("Hardware", "System", "Battery", "Network")) {
            out.add(Row(tab.uppercase(Locale.US), ""))
            for (r in DeviceInfo.rows(c, tab)) out.add(Row(r.label, r.value))
        }
        return out
    }

    private fun settingsRows(c: Context): List<Row> {
        val out = ArrayList<Row>()
        out.add(Row("Theme", Look.theme(c).name + " (" + Look.themeId(c) + ")"))
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
        out.add(Row("Search: devcheck", SearchSources.devCheck(c).toString()))
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

    // --------------------------------------------------------- user-friendliness
    /** The friction signals the shared standard asks for, counted. */
    // internal, not private: the unit tests in app/src/test exercise the counts
    // and the intent→outcome pairing directly.
    internal fun signalRows(events: List<Diag.Event>): List<Row> {
        fun count(name: String) = events.count { it.name == name }
        val out = ArrayList<Row>()
        out.add(Row("Sessions in log", count("session_start").toString()))
        out.add(Row("Rage taps", count("rage_tap").toString()))
        out.add(Row("Dead clicks", count("dead_click").toString()))
        out.add(Row("Permission loops", count("permission_loop").toString()))
        out.add(Row("Back-button spam", count("back_button_spam").toString()))
        out.add(Row("Errors shown", count("error_state_shown").toString()))
        out.add(Row("Freezes (anr)", count("anr").toString()))
        out.add(Row("Crashes", count("crash").toString()))

        val depths = events.filter { it.name == "scroll_depth" }
            .mapNotNull { it.props["percent"]?.toIntOrNull() }
        out.add(Row("Deepest scroll", if (depths.isEmpty()) "—" else depths.maxOrNull().toString() + "%"))

        val ttfv = events.lastOrNull { it.name == "time_to_first_value" }
            ?.props?.get("duration_ms")
        out.add(Row("Time to first action", ttfv?.let { it + " ms" } ?: "—"))

        val drops = events.filter { it.name == "drop_off" }
        out.add(Row("Session ends logged", drops.size.toString()))
        if (drops.isNotEmpty()) {
            out.add(Row("Last screen seen", drops.last().props["last_screen"] ?: "—"))
        }

        val launches = events.filter { it.name == "app_launch_result" }
        out.add(Row("Apps opened", launches.count { it.props["outcome"] == "success" }.toString()))
        out.add(Row("Stopped at the pause", launches.count { it.props["outcome"] == "cancelled" }.toString()))
        out.add(Row("Launch failures", launches.count { it.props["outcome"] == "failed" }.toString()))
        return out
    }

    /**
     * The rule the standard exists for: a log must capture intent *and* outcome.
     * Anything requested without a matching result is called out here.
     */
    internal fun healthRows(events: List<Diag.Event>): List<Row> {
        fun pair(intent: String, result: String, label: String): List<Row> {
            val intents = events.count { it.name == intent }
            val results = events.count { it.name == result }
            val unmatched = (intents - results).coerceAtLeast(0)
            return listOf(
                Row("$label intents", intents.toString()),
                Row("$label outcomes", results.toString()),
                Row("$label unmatched", if (unmatched == 0) "0 — every intent has an outcome" else unmatched.toString())
            )
        }
        val out = ArrayList<Row>()
        out.addAll(pair("access_fix_requested", "access_fix_result", "Permission fix"))
        out.addAll(pair("app_launch_requested", "app_launch_result", "App launch"))
        val ids = events.mapNotNull { it.props["attempt_id"] }.toSet()
        out.add(Row("Distinct attempts", ids.size.toString()))
        out.add(Row("Event types seen", events.map { it.name }.toSet().size.toString()))
        return out
    }

    /**
     * The categories the shared standard lists, with what this app collected for
     * each. A zero is honest too: it says the category applies but has not
     * happened yet, rather than hiding it.
     */
    private fun categoryRows(events: List<Diag.Event>): List<Row> = buildList {
        for (category in CATEGORIES) {
            val n = events.count { it.category == category }
            add(Row(category, n.toString()))
        }
        add(
            Row(
                "not collected",
                "database, api/network, server — this app has none of them"
            )
        )
    }

    /** Memory and the performance markers, so "it felt slow" has a number. */
    private fun performanceRows(c: Context, events: List<Diag.Event>): List<Row> = buildList {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        add(Row("Heap used", humanSize(used) + " of " + humanSize(runtime.maxMemory())))
        runCatching {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            add(Row("System free", humanSize(info.availMem)))
            add(Row("System total", humanSize(info.totalMem)))
            add(Row("Low memory", info.lowMemory.toString()))
            add(Row("Memory class", am.memoryClass.toString() + " MB"))
        }
        val markers = events.filter { it.name == "performance_marker" }
        add(Row("Slow renders", markers.size.toString() + " over 16 ms"))
        val slowest = markers.mapNotNull { it.props["duration_ms"]?.toLongOrNull() }.maxOrNull()
        add(Row("Slowest render", if (slowest == null) "—" else "$slowest ms"))
        val loads = events.filter { it.name == "app_list_loaded" }
            .mapNotNull { it.props["query_ms"]?.toLongOrNull() }
        add(Row("Slowest list load", if (loads.isEmpty()) "—" else loads.maxOrNull().toString() + " ms"))
        val queries = events.filter { it.name == "media_query_result" }
            .mapNotNull { it.props["query_ms"]?.toLongOrNull() }
        add(
            Row(
                "Slowest file search",
                if (queries.isEmpty()) "—" else queries.maxOrNull().toString() + " ms"
            )
        )
    }

    private fun privacyRows(): List<Row> = buildList {
        add(Row("Never logged", "email, phone, names, location, raw file paths"))
        add(Row("Never logged", "media file names, raw search queries, auth tokens"))
        add(Row("Search queries", "only the length is recorded, never the text"))
        add(Row("Redaction", "email → [email], long numbers → [number], paths → [path]"))
        add(Row("User identifier", "none — the app has no accounts, not even a hash"))
        add(Row("Retention", "14 days, pruned automatically on start"))
        add(Row("Size cap", "512 KB, rotated automatically"))
        add(Row("Access", "the app's private storage; nothing else on the phone can read it"))
        add(Row("Sampling", "high-volume info events 1 in 5; errors always kept"))
        add(Row("Transport", "none — the app cannot reach the network at all"))
        add(Row("Opt-out", "Settings → Record diagnostics"))
    }

    // ------------------------------------------------------------- rendering
    fun renderText(r: Report): String {
        val sb = StringBuilder()
        sb.append("LOW DISTRACTION — DIAGNOSTICS\n")
        sb.append("Version ").append(r.version).append(" (").append(r.versionCode).append(")")
        sb.append("   schema ").append(r.schemaVersion).append("\n")
        sb.append("Generated ").append(local(r.generatedAt)).append("\n")
        sb.append("Diagnostics ").append(if (r.enabled) "on" else "off")
        sb.append("  ·  ").append(r.events.size).append(" events")
        sb.append("  ·  ").append(r.sessions).append(" sessions")
        sb.append("  ·  ").append(r.crashCount).append(" crashes\n")
        sb.append("=".repeat(70)).append("\n\n")

        for (section in r.sections) {
            sb.append("[").append(section.title.uppercase(Locale.US)).append("]\n")
            val width = section.rows.filter { it.value.isNotEmpty() }.maxOfOrNull { it.key.length } ?: 0
            for (row in section.rows) {
                if (row.value.isEmpty()) {
                    sb.append("  ").append(row.key).append("\n")
                } else {
                    sb.append("  ").append(row.key.padEnd(width)).append("  ").append(row.value).append("\n")
                }
            }
            sb.append("\n")
        }

        sb.append("[EVENTS — ").append(r.events.size).append("]\n")
        if (r.events.isEmpty()) {
            sb.append("  (nothing recorded yet)\n")
        } else {
            for (e in r.events) {
                sb.append(LogFormat.stamp(e.time)).append("  ").append(e.level.padEnd(5)).append(" ")
                    .append(e.name)
                for ((k, v) in e.props) sb.append("  ").append(k).append('=').append(v)
                sb.append("\n")
            }
        }
        sb.append("\n")
        sb.append("Note: Low Distraction has no internet permission at all. This report\n")
        sb.append("was written on the device and only leaves it because you chose to\n")
        sb.append("export or send it.\n")
        return sb.toString()
    }

    fun renderMarkdown(r: Report): String {
        val sb = StringBuilder()
        sb.append("# Low Distraction — diagnostics\n\n")
        sb.append("**Version** ").append(r.version).append(" (").append(r.versionCode)
            .append(") · **schema** ").append(r.schemaVersion).append("  \n")
        sb.append("**Generated** ").append(local(r.generatedAt)).append("  \n")
        sb.append("**Diagnostics** ").append(if (r.enabled) "on" else "off")
            .append(" · ").append(r.events.size).append(" events · ")
            .append(r.sessions).append(" sessions · ").append(r.crashCount)
            .append(" crashes\n\n")

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

        sb.append("## Events\n\n")
        if (r.events.isEmpty()) {
            sb.append("_Nothing recorded yet._\n\n")
        } else {
            sb.append("```text\n")
            for (e in r.events) {
                sb.append(LogFormat.stamp(e.time)).append("  ").append(e.level.padEnd(5)).append(" ")
                    .append(e.name)
                for ((k, v) in e.props) sb.append("  ").append(k).append('=').append(v)
                sb.append("\n")
            }
            sb.append("```\n\n")
        }
        sb.append("_Low Distraction has no internet permission; this report was written on ")
        sb.append("the device and only leaves it because you chose to export it._\n")
        return sb.toString()
    }

    /** The exact structured records — one JSON object per event. */
    fun renderJson(r: Report): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"app\": ").append(json(r.appName)).append(",\n")
        sb.append("  \"package\": ").append(json(packageOf(r))).append(",\n")
        sb.append("  \"versionName\": ").append(json(r.version)).append(",\n")
        sb.append("  \"versionCode\": ").append(r.versionCode).append(",\n")
        sb.append("  \"schemaVersion\": ").append(json(r.schemaVersion)).append(",\n")
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
        sb.append("  \"events\": [\n")
        for ((i, raw) in r.rawEvents.withIndex()) {
            sb.append("    ").append(raw).append(if (i == r.rawEvents.size - 1) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    /** One row per event, with every property as a column. */
    /** One report, in whichever shape was asked for. */
    fun render(r: Report, ext: String): String = when (ext) {
        "md" -> renderMarkdown(r)
        "json" -> renderJson(r)
        "csv" -> renderCsv(r)
        else -> renderText(r)
    }

    /**
     * All four shapes in one archive, so a single file carries everything.
     *
     * Pure, like the other renderers, and covered by a unit test that unzips the
     * result and compares each entry with the matching renderer.
     */
    fun renderZip(r: Report): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (ext in TEXT_FORMATS) {
                zip.putNextEntry(ZipEntry("lowdistraction-diagnostics.$ext"))
                zip.write(render(r, ext).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The four text shapes; a .zip bundles exactly these. */
    val TEXT_FORMATS = arrayOf("txt", "md", "json", "csv")

    fun renderCsv(r: Report): String {
        val columns = ArrayList<String>()
        for (k in listOf(
            "timestamp", "log_level", "category", "event_name", "session_id", "screen_or_route"
        )) {
            columns.add(k)
        }
        val extra = LinkedHashSet<String>()
        for (e in r.events) for (k in e.props.keys) extra.add(k)
        columns.addAll(extra.sorted())

        val sb = StringBuilder()
        sb.append(columns.joinToString(",") { csv(it) }).append('\n')
        for (e in r.events) {
            val cells = ArrayList<String>()
            cells.add(LogFormat.stamp(e.time))
            cells.add(e.level)
            cells.add(e.category)
            cells.add(e.name)
            cells.add(e.session)
            cells.add(e.screen)
            for (k in extra.sorted()) cells.add(e.props[k] ?: "")
            sb.append(cells.joinToString(",") { csv(it) }).append('\n')
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

    /** The categories this app can produce, in the order the report lists them. */
    private val CATEGORIES = listOf(
        "application", "activity", "error", "security",
        "performance", "audit", "background", "debug"
    )

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

    // The three encodings live in LogFormat, where they are unit-tested.
    private fun mdCell(s: String): String = LogFormat.markdown(s)
    private fun csv(s: String): String = LogFormat.csv(s)
    private fun json(s: String): String = LogFormat.json(s)
}
