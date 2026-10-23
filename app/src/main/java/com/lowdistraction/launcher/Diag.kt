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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The structured diagnostics log.
 *
 * One JSON object per line, in the app's own private storage. Every record is
 * nested the way the unified diagnostics plan asks for: schema_version, event_id,
 * timestamp, observed_timestamp, source, severity, category, event_name, message,
 * context, correlation, error, metrics and privacy — see
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

    const val SCHEMA_VERSION = "2.0.0"
    const val PLATFORM = "android"
    const val ENVIRONMENT = "production"

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

    /** A stack trace is worth far more room than an ordinary property. */
    private const val TRACE_CAP = 4000

    private const val LEVEL_FATAL = "fatal"

    /** High-volume events only. Anything an error, or part of an intent→outcome
     *  pair, is never sampled. */
    private val SAMPLED =
        setOf("scroll_depth", "screen_view", "performance_marker", "media_query_result")

    /** The envelope's own keys, at the top level of a nested record. */
    val ENVELOPE = setOf(
        "schema_version", "event_id", "timestamp", "observed_timestamp", "source",
        "severity", "category", "event_name", "message", "context", "correlation",
        "error", "metrics", "privacy", "attributes"
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
        // The same pass looks for a crash at the very end of the log, which is
        // the only way a crash loop can show up from inside the app.
        if (first) {
            val ctx = c.applicationContext
            runCatching {
                writer.execute {
                    prune(ctx)
                    reportPreviousCrash(ctx)
                }
            }
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
        if (level == LEVEL_FATAL) {
            // A fatal event is the last thing this process may ever write. The
            // queue would die with the process, so write it now, on this thread —
            // the one place blocking is the right answer.
            append(c.applicationContext, line)
            return
        }
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
        val now = System.currentTimeMillis()
        val iso = LogFormat.isoUtc(now)

        // Each property goes to the group its name belongs to. Anything the
        // schema does not place lands in attributes, and the CI check keeps this
        // table and the schema in step.
        val groups = LinkedHashMap<String, JSONObject>()
        for (group in listOf(
            LogGroups.CONTEXT, LogGroups.CORRELATION, LogGroups.ERROR,
            LogGroups.METRICS, LogGroups.ATTRIBUTES
        )) {
            groups[group] = JSONObject()
        }
        var attempt: String? = null
        for ((k, v) in props) {
            if (v == null) continue
            if (k == "attempt_id") {
                attempt = v.toString()
                continue
            }
            val group = LogGroups.of(k)
            val target = groups.getValue(group)
            val cap = if (k == "stack_trace") TRACE_CAP else LogFormat.VALUE_CAP
            when (v) {
                is Int -> target.put(k, v)
                is Long -> target.put(k, v)
                is Boolean -> target.put(k, v)
                is Double -> target.put(k, v)
                is Float -> target.put(k, v.toDouble())
                else -> target.put(k, LogFormat.redact(v.toString(), cap))
            }
        }

        val context = groups.getValue(LogGroups.CONTEXT)
        if (!context.has("screen")) context.put("screen", currentScreen)
        context.put("feature", featureOf(name))
        context.put("operation", name)

        val o = JSONObject()
        o.put("schema_version", SCHEMA_VERSION)
        o.put("event_id", "evt_" + shortId())
        o.put("timestamp", iso)
        // Nothing ingests these records, so the source clock and our clock are
        // the same reading. Both are kept because the standard asks for both.
        o.put("observed_timestamp", iso)
        o.put("source", JSONObject().apply {
            put("app_id", c.packageName)
            put("platform", PLATFORM)
            put("environment", ENVIRONMENT)
            put("app_version", appVersion(c))
            put("build", versionCode(c))
            put("os", osName())
            put("device_model", deviceModel())
            put("network_type", networkType(c))
        })
        o.put("severity", level)
        o.put("category", category)
        o.put("event_name", name)
        o.put("message", LogFormat.redact(summary(name, props)))
        o.put("context", context)
        o.put("correlation", groups.getValue(LogGroups.CORRELATION).apply {
            put("session_id", sessionId())
            put("attempt_id", attempt ?: JSONObject.NULL)
            put("request_id", JSONObject.NULL)
            put("trace_id", JSONObject.NULL)
        })
        val error = groups.getValue(LogGroups.ERROR)
        o.put("error", if (error.length() > 0) error else JSONObject.NULL)
        val metrics = groups.getValue(LogGroups.METRICS)
        o.put("metrics", if (metrics.length() > 0) metrics else JSONObject.NULL)
        o.put("privacy", JSONObject().apply {
            put("redacted", true)
            // Not a proof of anything — the standard says so itself. It records
            // that the redaction pass ran and no check flagged personal data.
            put("contains_personal_data", false)
        })
        val attributes = groups.getValue(LogGroups.ATTRIBUTES)
        if (attributes.length() > 0) o.put("attributes", attributes)
        return o.toString()
    }

    /** A readable one-liner for the log explorer: the name, then the gist. */
    private fun summary(name: String, props: List<Pair<String, Any?>>): String {
        val gist = props.asSequence()
            .filter { it.second != null }
            .take(4)
            .joinToString(" ") { it.first + "=" + it.second }
        return if (gist.isEmpty()) name else name + " — " + gist
    }

    /** A coarse feature name, so events can be grouped without another field. */
    private fun featureOf(name: String): String = name.substringBefore('_')

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

    /**
     * If the last thing the log holds is a crash, the previous run died from it.
     * Recording that on the next start is what turns a crash loop into something
     * the report can show.
     */
    private fun reportPreviousCrash(c: Context) {
        val last = tail(c).lastOrNull() ?: return
        val o = runCatching { JSONObject(last) }.getOrNull() ?: return
        if (o.optString("event_name") != "crash") return
        // The exception type is in `error` and the screen in `context`: a crash
        // record is nested like every other one.
        val error = o.optJSONObject("error")
        val context = o.optJSONObject("context")
        Events.previousRunCrashed(
            c,
            error?.optString("exception_type", "unknown") ?: "unknown",
            context?.optString("screen", "—") ?: "—",
            o.optString("timestamp", "")
        )
    }

    /** How long the process had been alive, for a crash or an anr. */
    private fun processUptimeMs(): Long =
        if (processStartedAt == 0L) 0L else System.currentTimeMillis() - processStartedAt

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
            val severity = o.optString("severity", "INFO")
            val context = o.optJSONObject("context")
            val correlation = o.optJSONObject("correlation")

            // Flattened for display and for the spreadsheet: the groups are how
            // it is stored, not how it is read.
            val props = LinkedHashMap<String, String>()
            for (group in listOf("context", "metrics", "error", "attributes")) {
                val g = o.optJSONObject(group) ?: continue
                val keys = g.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    props[k] = g.optString(k, "")
                }
            }
            correlation?.optString("attempt_id", "")
                ?.takeIf { it.isNotEmpty() && it != "null" }
                ?.let { props["attempt_id"] = it }

            out.add(
                Event(
                    name, time, severity,
                    o.optString("category", "application"),
                    context?.optString("screen", "—") ?: "—",
                    correlation?.optString("session_id", "") ?: "",
                    props
                )
            )
        }
        return out
    }

    /**
     * Waits for the writer to catch up.
     *
     * Called before a report is built: without it, the events of the last few
     * milliseconds could still be in the queue, and the report would be missing
     * exactly the most recent thing that happened.
     */
    fun flush() {
        runCatching { writer.submit { }.get(2, TimeUnit.SECONDS) }
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
                    val trace = sw.toString()
                    val onMain = runCatching {
                        Looper.getMainLooper()?.thread === thread
                    }.getOrDefault(false)
                    Events.crash(
                        ctx,
                        throwable.javaClass.name,
                        hash(trace),
                        trace,
                        currentScreen,
                        lastAction,
                        thread.name,
                        onMain,
                        processUptimeMs()
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
