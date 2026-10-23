package com.lowdistraction.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encodings and the redaction rules, tested on the JVM because LogFormat is
 * deliberately free of Android.
 *
 * These are the pieces that were hand-rolled, so they are the pieces most worth
 * pinning down: a broken escape makes the JSON export unparseable, and a broken
 * redaction rule leaks something the app promised never to record.
 */
class LogFormatTest {

    // ------------------------------------------------------------------ json

    @Test
    fun `json escapes the characters that would break a line`() {
        assertEquals("\"a\\\"b\"", LogFormat.json("a\"b"))
        assertEquals("\"a\\\\b\"", LogFormat.json("a\\b"))
        assertEquals("\"a\\nb\"", LogFormat.json("a\nb"))
        assertEquals("\"a\\rb\"", LogFormat.json("a\rb"))
        assertEquals("\"a\\tb\"", LogFormat.json("a\tb"))
    }

    @Test
    fun `json escapes other control characters as unicode`() {
        assertEquals("\"\\u0001\"", LogFormat.json("\u0001"))
    }

    @Test
    fun `json leaves ordinary text and non-ascii alone`() {
        assertEquals("\"hello world\"", LogFormat.json("hello world"))
        assertEquals("\"naam — theek\"", LogFormat.json("naam — theek"))
    }

    @Test
    fun `a round trip through the json encoding is stable`() {
        val awkward = "quote \" backslash \\ newline \n tab \t done"
        val encoded = LogFormat.json(awkward)
        // One line, and both ends quoted: nothing escaped can break the line.
        assertTrue(encoded.startsWith("\""))
        assertTrue(encoded.endsWith("\""))
        assertEquals(-1, encoded.indexOf('\n'))
    }

    // ------------------------------------------------------------------- csv

    @Test
    fun `csv quotes every field`() {
        assertEquals("\"plain\"", LogFormat.csv("plain"))
    }

    @Test
    fun `csv doubles inner quotes and keeps commas inside the quotes`() {
        assertEquals("\"a\"\"b\"", LogFormat.csv("a\"b"))
        assertEquals("\"a,b\"", LogFormat.csv("a,b"))
        assertEquals("\"a\nb\"", LogFormat.csv("a\nb"))
    }

    // -------------------------------------------------------------- markdown

    @Test
    fun `markdown escapes a pipe so the table survives`() {
        assertEquals("a\\|b", LogFormat.markdown("a|b"))
    }

    @Test
    fun `markdown flattens a newline and shows a dash for nothing`() {
        assertEquals("a b", LogFormat.markdown("a\nb"))
        assertEquals("—", LogFormat.markdown(""))
    }

    // -------------------------------------------------------------- redaction

    @Test
    fun `redaction removes an email address`() {
        assertEquals("[email]", LogFormat.redact("br19shc39@gmail.com"))
        assertEquals(
            "write to [email] please",
            LogFormat.redact("write to someone@example.co.in please")
        )
    }

    @Test
    fun `redaction removes a long digit run but keeps short numbers`() {
        assertEquals("[number]", LogFormat.redact("9876543210"))
        assertEquals("12345 is short", LogFormat.redact("12345 is short"))
    }

    @Test
    fun `redaction removes a filesystem path`() {
        assertEquals(
            "[path]",
            LogFormat.redact("/storage/emulated/0/Download/statement.pdf")
        )
    }

    @Test
    fun `redaction caps the length so one value cannot bloat a line`() {
        val redacted = LogFormat.redact("x".repeat(1000))
        assertEquals(LogFormat.VALUE_CAP + 1, redacted.length)
        assertTrue(redacted.endsWith("…"))
    }

    @Test
    fun `redaction leaves ordinary values untouched`() {
        assertEquals("com.example.app", LogFormat.redact("com.example.app"))
        assertEquals("wifi", LogFormat.redact("wifi"))
    }

    // ------------------------------------------------------------- timestamps

    @Test
    fun `the envelope timestamp is iso 8601 in utc`() {
        val iso = LogFormat.isoUtc(1_700_000_000_000L)
        assertEquals("2023-11-14T22:13:20Z", iso)
    }

    @Test
    fun `an iso timestamp parses back to the same instant`() {
        val time = 1_700_000_000_000L
        assertEquals(time, LogFormat.parseIso(LogFormat.isoUtc(time)) ?: 0L)
    }

    @Test
    fun `an unparseable timestamp is null rather than an exception`() {
        assertEquals(null, LogFormat.parseIso("not a timestamp"))
    }
}
