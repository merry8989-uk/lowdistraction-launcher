package com.lowdistraction.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The structured diagnostics log.
 *
 * One JSON object per line, in the app's own private storage. Every record
 * carries the common event envelope (schema_version, timestamp, session_id,
 * platform, app_version, os, device_model, network_type, screen_or_route,
 * log_level, release_channel) plus that event's own properties — see
 * `tools/logging_schema.json` for the schema and `docs/logging.md` for the
 * reasoning.
 *
 * House rules, all enforced or checked in CI:
 *  - Every event is declared in [Events]; nothing else calls [event] directly,
 *    and `tools/validate_logging.py` fails the build on an unknown event or
 *    property.
 *  - No PII. Values are redacted (email, long digit runs, filesystem paths) and
 *    the user's own file names and raw search queries are never passed in.
 *  - Diagnostics are ON by default and can be switched off in Settings; when off
 *    nothing at all is written.
 *  - Records older than [RETENTION_DAYS] are dropped on start, and the file is
 *    rotated at [MAX_BYTES], so it can never grow without end.
 *  - There is no transport. The app has no internet permission; a log leaves the
 *    phone only when the user exports, shares or mails it.
 */
object Diag {

    const val SCHEMA_VERSION = "1.1.0"
    const val PLATFORM = "android"
    const val RELEASE_CHANNEL = "production"

    private const val PREFS = "diagnostics_prefs"
    private const val KEY_ENABLED = "enabled"

    const val FILE_NAME = "diagnostics.log"

    private const val MAX_BYTES = 512L * 1024L
    private const val RETENTION_DAYS = 14L
    private const val MAX_READ = 5000
    private const val SAMPLE_1_IN = 5
    private const val TICK_MS = 1000L
    private const val STALL_MS = 2000L

    /** Lines allowed to be waiting for the disk before new ones are dropped. */
    private const val MAX_QUEUE = 500

    /** High-volume events only. Anything an error, or part of an intent→outcome
     *  pair, is never sampled. */
    private val SAMPLED =
        setOf("scroll_depth", "screen_view", "performance_marker", "media_query_result")

    /** Keys that belong to the envelope rather than to an individual event. */
    val ENVELOPE = setOf(
        "event_name", "schema_version", "timestamp", "session_id", "attempt_id",
        "platform", "app_id", "app_version", "os", "device_model", "network_type",
        "screen_or_route", "category", "log_level", "release_channel"
    )

    data class Event(
        val name: String,
        val time: Long,
        val level: String,
        val category: String,
        val screen: String,
        val session: String,
        val props: Map<String, String>
    )

    private val lock = Any()
    private val sampleCounter = AtomicInteger(0)
    private val fixAttempts = HashMap<String, Int>()

    /**
     * One background thread writes every line, so no screen ever waits on the
     * disk. The line itself is built on the calling thread — that keeps the
     * timestamp and the current screen true to the moment the event happened —
     * and only the writing is handed over.
     */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diag-writer").apply { isDaemon = true }
    }

    /** Lines waiting for the writer; a backstop, not a queue limit. */
    private val queued = AtomicInteger(0)

    private var appContext: Context? = null
    private var sessionId: String? = null
    private var sessionStartAt = 0L
    private var processStartedAt = 0L
    private var crashHooked = false

    @Volatile private var currentScreen = "—"
    @Volatile private var lastAction = "—"
    @Volatile private var screenChangedAt = 0L

    private val watchHandler = Handler(Looper.getMainLooper())
    private var watchRunnable: Runnable? = null
    private var lastTick = 0L

    // ------------------------------------------------------------- on / off
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Diagnostics are ON unless the user turns them off. */
    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
        // Written after the flag, so switching it on records itself.
        Events.diagnosticsToggled(c, on)
    }

    fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    // --------------------------------------------------------------- session
    /** Called first thing from the Application, so a cold start can be timed. */
    fun markProcessStart() {
        processStartedAt = System.currentTimeMillis()
    }

    /** Milliseconds since the process was created, or 0 if it was not marked. */
    fun coldStartMs(): Long =
        if (processStartedAt == 0L) 0L else System.currentTimeMillis() - processStartedAt

    /** Installs the crash handler early; safe to call repeatedly. */
    fun installCrashHandler() {
        val install = synchronized(lock) {
            if (crashHooked) {
                false
            } else {
                crashHooked = true
                true
            }
        }
        if (install) hookCrashes()
    }

    /**
     * Begins or continues the session. The first call in a process is the cold
     * start and creates the session id; a later call is a warm start and keeps
     * it, so one launch is one session.
     */
    fun startSession(c: Context, coldMs: Long, warmMs: Long) {
        val ctx = c.applicationContext
        val first = synchronized(lock) {
            if (sessionId == null) {
                sessionId = "sess_" + shortId()
                appContext = ctx
                sessionStartAt = System.currentTimeMillis()
                true
            } else {
                false
            }
        }
        // Pruning reads (and may rewrite) the whole file, so it belongs on the
        // writer thread — the session_start line is queued behind it, in order.
        if (first) {
            val ctx = c.applicationContext
            runCatching { writer.execute { prune(ctx) } }
        }
        if (first) {
            Events.sessionStart(c, Events.StartType.COLD, coldMs, 0L)
        } else {
            Events.sessionStart(c, Events.StartType.WARM, 0L, warmMs)
        }
        startWatch(c)
    }

    /** The last event before the app leaves the foreground. */
    fun endSession(c: Context) {
        stopWatch()
        Events.dropOff(c, currentScreen, lastAction, sessionDurationMs())
    }

    fun sessionId(): String = sessionId ?: "sess_none"

    fun sessionDurationMs(): Long =
        if (sessionStartAt == 0L) 0L else System.currentTimeMillis() - sessionStartAt

    fun currentScreen(): String = currentScreen

    fun setScreen(name: String) {
        currentScreen = name
        screenChangedAt = System.currentTimeMillis()
    }

    /** How long the current screen has been up, for `screen_view`. */
    fun timeOnScreenMs(): Long =
        if (screenChangedAt == 0L) 0L else System.currentTimeMillis() - screenChangedAt

    fun setLastAction(action: String) {
        lastAction = action
    }

    fun lastAction(): String = lastAction

    /** A fresh correlation id for one user action and its outcome. */
    fun newAttempt(): String = "att_" + shortId()

    /** Counts fixes requested for one permission in this session. */
    fun bumpFixAttempt(permission: String): Int = synchronized(lock) {
        val n = (fixAttempts[permission] ?: 0) + 1
        fixAttempts[permission] = n
        n
    }

    // --------------------------------------------------------------- writing
    /**
     * The one low-level writer. **Only [Events] should call this** — the CI
     * schema check relies on that.
     */
    fun event(
        c: Context,
        name: String,
        level: String,
        category: String,
        props: List<Pair<String, Any?>>
    ) {
        if (!isEnabled(c)) return
        if (name in SAMPLED && level == "info" && sampleCounter.incrementAndGet() % SAMPLE_1_IN != 0) {
            return
        }
        val line = runCatching { line(c, name, level, category, props) }.getOrNull() ?: return
        enqueue(c, line)
    }

    /** Builds the envelope and the event's own properties into one JSON line. */
    private fun line(
        c: Context,
        name: String,
        level: String,
        category: String,
        props: List<Pair<String, Any?>>
    ): String {
        val o = JSONObject()
        o.put("event_name", name)
        o.put("schema_version", SCHEMA_VERSION)
        o.put("timestamp", LogFormat.isoUtc(System.currentTimeMillis()))
        o.put("session_id", sessionId())
        o.put("platform", PLATFORM)
        o.put("app_id", c.packageName)
        o.put("app_version", appVersion(c))
        o.put("os", osName())
        o.put("device_model", deviceModel())
        o.put("network_type", networkType(c))
        o.put("screen_or_route", currentScreen)
        o.put("category", category)
        o.put("log_level", level)
        o.put("release_channel", RELEASE_CHANNEL)

        for ((k, v) in props) {
            if (v == null) continue
            when (v) {
                is Int -> o.put(k, v)
                is Long -> o.put(k, v)
                is Boolean -> o.put(k, v)
                is Double -> o.put(k, v)
                is Float -> o.put(k, v.toDouble())
                else -> o.put(k, LogFormat.redact(v.toString()))
            }
        }
        return o.toString()
    }

    /** Hands a line to the writer thread. Never blocks the caller on the disk. */
    private fun enqueue(c: Context, line: String) {
        // If the disk has fallen behind, drop rather than grow without end.
        if (queued.get() >= MAX_QUEUE) return
        val ctx = c.applicationContext
        queued.incrementAndGet()
        runCatching {
            writer.execute {
                try {
                    append(ctx, line)
                } finally {
                    queued.decrementAndGet()
                }
            }
        }.onFailure { queued.decrementAndGet() }
    }

    /** Runs on the writer thread only. */
    private fun append(c: Context, line: String) {
        runCatching {
            val f = file(c)
            if (f.length() > MAX_BYTES) f.delete()
            f.appendText(line + "\n")
        }
    }

    // --------------------------------------------------------------- reading
    fun sizeBytes(c: Context): Long = runCatching { file(c).length() }.getOrDefault(0L)

    /** The raw JSON lines, for an exact structured export. */
    fun rawEvents(c: Context): List<String> = tail(c).mapNotNull { raw ->
        runCatching { JSONObject(raw) }.getOrNull()?.let { raw }
    }

    /**
     * The same records, parsed for display and for the spreadsheet export.
     *
     * Reads and parses the file, so call it off the main thread —
     * [DiagnosticsReport.collect] does exactly that. Writing is never done on the
     * caller's thread; reading still is.
     */
    fun entries(c: Context): List<Event> {
        val out = ArrayList<Event>()
        for (raw in rawEvents(c)) {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val name = o.optString("event_name", "")
            if (name.isEmpty()) continue
            val time = LogFormat.parseIso(o.optString("timestamp", "")) ?: 0L
            val level = o.optString("log_level", "info")
            val props = LinkedHashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in ENVELOPE) continue
                props[k] = o.optString(k, "")
            }
            out.add(
                Event(
                    name, time, level,
                    o.optString("category", "application"),
                    o.optString("screen_or_route", "—"),
                    o.optString("session_id", ""),
                    props
                )
            )
        }
        return out
    }

    fun clear(c: Context) {
        val ctx = c.applicationContext
        // Counting, wiping and recording all happen on the writer thread, so the
        // screen never waits — and the wipe lands before the event that reports it.
        runCatching {
            writer.execute {
                val before = lineCount(ctx)
                runCatching { file(ctx).writeText("") }
                Events.diagnosticsAction(ctx, Events.DiagAction.CLEAN, null, null, null, before)
            }
        }
    }

    /**
     * How many records the file holds, without parsing a single one. Reads the
     * file, so prefer the writer thread or a worker.
     */
    fun lineCount(c: Context): Int = runCatching {
        val f = file(c)
        if (!f.exists() || f.length() == 0L) return@runCatching 0
        var n = 0
        f.bufferedReader().use { reader ->
            while (true) {
                val row = reader.readLine() ?: break
                if (row.isNotBlank()) n++
            }
        }
        n
    }.getOrDefault(0)

    private fun tail(c: Context): List<String> = runCatching {
        val f = file(c)
        if (!f.exists() || f.length() == 0L) return@runCatching emptyList()
        val len = f.length()
        val skip = if (len > MAX_BYTES) len - MAX_BYTES else 0L
        val text = RandomAccessFile(f, "r").use { raf ->
            raf.seek(skip)
            val buf = ByteArray((len - skip).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
        val body = if (skip > 0L) text.substringAfter('\n', "") else text
        val lines = body.split('\n').filter { it.isNotBlank() }
        if (lines.size > MAX_READ) lines.subList(lines.size - MAX_READ, lines.size) else lines
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------ retention
    /** Drops records older than the retention window. */
    private fun prune(c: Context) {
        runCatching {
            val f = file(c)
            if (!f.exists() || f.length() == 0L) return@runCatching
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L
            val kept = ArrayList<String>()
            var dropped = 0
            for (line in f.readLines()) {
                if (line.isBlank()) continue
                val t = runCatching { JSONObject(line) }.getOrNull()
                    ?.let { LogFormat.parseIso(it.optString("timestamp", "")) } ?: 0L
                if (t in 1L until cutoff) dropped++ else kept.add(line)
            }
            if (dropped > 0) {
                f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
            }
        }
    }

    // -------------------------------------------------------------- watchdog
    /** Watches the main thread so a freeze is recorded as an event, not a rumour. */
    fun startWatch(c: Context) {
        if (watchRunnable != null) return
        lastTick = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val gap = now - lastTick
                lastTick = now
                if (gap > STALL_MS) {
                    val ctx = appContext
                    if (ctx != null) Events.anr(ctx, gap, currentScreen)
                }
                watchHandler.postDelayed(this, TICK_MS)
            }
        }
        watchRunnable = r
        watchHandler.postDelayed(r, TICK_MS)
    }

    fun stopWatch() {
        watchRunnable?.let { watchHandler.removeCallbacks(it) }
        watchRunnable = null
    }

    // --------------------------------------------------------------- crashes
    private fun hookCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val ctx = appContext
            if (ctx != null) {
                runCatching {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    Events.crash(
                        ctx,
                        throwable.javaClass.name,
                        hash(sw.toString()),
                        currentScreen,
                        lastAction,
                        thread.name
                    )
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ---------------------------------------------------------------- values
    fun appVersion(c: Context): String =
        versionName(c) + " (" + versionCode(c) + ")"

    fun versionName(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    @Suppress("DEPRECATION")
    fun versionCode(c: Context): Long = runCatching {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun osName(): String =
        "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"

    private fun deviceModel(): String = (Build.MANUFACTURER + " " + Build.MODEL).trim()

    private fun networkType(c: Context): String = runCatching {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "offline"
        }
    }.getOrDefault("offline")

    // Timestamps, redaction and the output encodings all live in [LogFormat],
    // which is deliberately free of Android so it can be unit-tested on the JVM.

    private fun shortId(): String =
        UUID.randomUUID().toString().replace("-", "").take(8)

    private fun hash(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }
            .take(12)
    }.getOrDefault("—")
}
