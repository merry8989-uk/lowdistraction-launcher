package com.lowdistraction.launcher

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The in-app diagnostics log.
 *
 * Everything is appended to one plain-text file inside the app's own private
 * storage. Nothing is ever uploaded and the app has no internet permission, so
 * the log can only leave the phone when the user exports it themselves from
 * Settings → Diagnostics.
 *
 * Diagnostics are **on by default** and can be switched off in Settings; when
 * they are off nothing at all is written.
 *
 * The file is one record per line, tab separated:
 *
 *     epochMillis \t LEVEL \t tag \t escaped-message
 *
 * Escaping keeps multi-line messages (a stack trace, say) on one line so the
 * file can be parsed back exactly for the CSV and JSON exports.
 */
object Diag {

    private const val PREFS = "diagnostics_prefs"
    private const val KEY_ENABLED = "enabled"

    /** Rotate once the log passes this size, so it can never grow without end. */
    private const val MAX_BYTES = 400L * 1024L

    /** Most recent records kept when reading the log back for a report. */
    const val MAX_READ = 4000

    const val FILE_NAME = "diagnostics.log"

    data class Entry(val time: Long, val level: String, val tag: String, val message: String)

    private val lock = Any()
    private var appContext: Context? = null

    // ------------------------------------------------------------- on / off
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Diagnostics are ON unless the user turns them off. */
    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) info(c, "diagnostics", "Diagnostics switched on")
    }

    fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    /**
     * Wires up the crash handler and notes the app version. Safe to call from
     * every entry point — only the first call does any work.
     */
    fun install(c: Context) {
        val ctx = c.applicationContext
        val first = synchronized(lock) {
            if (appContext == null) {
                appContext = ctx
                true
            } else {
                false
            }
        }
        if (first) {
            hookCrashes()
            info(ctx, "diagnostics", "Diagnostics ready — v" + versionName(ctx))
        }
    }

    // -------------------------------------------------------------- writing
    fun info(c: Context, tag: String, message: String) = write(c, "INFO", tag, message)

    fun warn(c: Context, tag: String, message: String) = write(c, "WARN", tag, message)

    fun error(c: Context, tag: String, message: String) = write(c, "ERROR", tag, message)

    fun write(c: Context, level: String, tag: String, message: String) {
        if (!isEnabled(c)) return
        runCatching {
            val f = file(c)
            synchronized(lock) {
                if (f.length() > MAX_BYTES) f.delete()
                f.appendText(
                    System.currentTimeMillis().toString() + "\t" + level + "\t" +
                        tag.replace('\t', ' ') + "\t" + escape(message) + "\n"
                )
            }
        }
    }

    // -------------------------------------------------------------- reading
    fun sizeBytes(c: Context): Long = runCatching { file(c).length() }.getOrDefault(0L)

    /** The most recent records, oldest first. Never throws. */
    fun entries(c: Context): List<Entry> = runCatching {
        val f = file(c)
        if (!f.exists() || f.length() == 0L) return@runCatching emptyList()

        // Read the tail only, so a huge log cannot exhaust memory.
        val maxBytes = 512L * 1024L
        val len = f.length()
        val skip = if (len > maxBytes) len - maxBytes else 0L
        val text = RandomAccessFile(f, "r").use { raf ->
            raf.seek(skip)
            val buf = ByteArray((len - skip).toInt())
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
        // Drop the partial first line when we started mid-file.
        val body = if (skip > 0L) text.substringAfter('\n', "") else text

        val out = ArrayList<Entry>()
        for (raw in body.split('\n')) {
            if (raw.isEmpty()) continue
            val parts = raw.split('\t')
            if (parts.size < 4) continue
            val time = parts[0].toLongOrNull() ?: continue
            out.add(Entry(time, parts[1], parts[2], unescape(parts.subList(3, parts.size).joinToString("\t"))))
        }
        if (out.size > MAX_READ) out.subList(out.size - MAX_READ, out.size) else out
    }.getOrDefault(emptyList())

    fun clear(c: Context) {
        runCatching { file(c).writeText("") }
        info(c, "diagnostics", "Log cleared by the user")
    }

    // -------------------------------------------------------------- crashes
    private fun hookCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val ctx = appContext
            if (ctx != null) {
                runCatching {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    write(ctx, "FATAL", "crash", "Uncaught on ${thread.name}\n$sw")
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ------------------------------------------------------------- helpers
    fun versionName(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    @Suppress("DEPRECATION")
    fun versionCode(c: Context): Long = runCatching {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    fun stamp(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\r", "")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(ch); i++ }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
