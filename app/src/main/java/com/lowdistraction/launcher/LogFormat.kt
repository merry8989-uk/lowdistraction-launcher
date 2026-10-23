package com.lowdistraction.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The pure half of the logging pipeline: timestamps, redaction and the output
 * encodings.
 *
 * Nothing here touches Android, and that is the point — this is the part that
 * can be, and is, unit-tested on the JVM. See `app/src/test`. Keeping it apart
 * from [Diag] is deliberate: [Diag] holds a `Handler` on the main looper, which
 * cannot be constructed off a device.
 */
object LogFormat {

    /** A value longer than this is cut, so one runaway string cannot bloat a line. */
    const val VALUE_CAP = 240

    private const val LOCAL_STAMP = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val ISO_UTC = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    private val emailPattern =
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val digitRun = Regex("\\d{7,}")
    private val pathPattern = Regex("(?:/[\\w.\\-]+){2,}")

    /** Local wall-clock time, for the human-readable reports. */
    fun stamp(time: Long): String =
        SimpleDateFormat(LOCAL_STAMP, Locale.US).format(Date(time))

    /** ISO 8601 in UTC, which is what the envelope carries. */
    fun isoUtc(time: Long): String =
        SimpleDateFormat(ISO_UTC, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(time))

    fun parseIso(s: String): Long? = runCatching {
        SimpleDateFormat(ISO_UTC, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(s)?.time
    }.getOrNull()

    /**
     * The same rules the web side uses: no email address, no long digit run and
     * no filesystem path survives, and the whole value is capped.
     *
     * The cap is a parameter because one value — a crash stack trace — is worth
     * far more room than an ordinary property.
     */
    fun redact(value: String, cap: Int = VALUE_CAP): String {
        var s = emailPattern.replace(value, "[email]")
        s = digitRun.replace(s, "[number]")
        s = pathPattern.replace(s, "[path]")
        return if (s.length > cap) s.substring(0, cap) + "…" else s
    }

    /** One JSON string literal, with everything JSON requires escaped. */
    fun json(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (ch < ' ') {
                        sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
            }
        }
        return sb.append('"').toString()
    }

    /** One CSV field, quoted the way RFC 4180 asks. */
    fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    /** One markdown table cell; an unescaped pipe would break the table. */
    fun markdown(s: String): String =
        s.replace("|", "\\|").replace("\n", " ").ifEmpty { "—" }
}
