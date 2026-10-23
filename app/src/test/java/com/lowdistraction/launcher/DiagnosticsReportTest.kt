package com.lowdistraction.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The report renderers, the hand-rolled zip, and the counts behind the report's
 * Signals and Log health sections.
 *
 * All of it is pure — no Context, no device — which is why it can run here.
 */
class DiagnosticsReportTest {

    private val when0 = 1_700_000_000_000L

    private fun event(
        name: String,
        props: Map<String, String> = emptyMap(),
        category: String = "activity",
        level: String = "info"
    ) = Diag.Event(
        name = name,
        time = when0,
        level = level,
        category = category,
        screen = "Home",
        session = "sess_test",
        props = props
    )

    private fun report(vararg events: Diag.Event) = DiagnosticsReport.Report(
        generatedAt = when0,
        appName = "Low Distraction",
        version = "1.36",
        versionCode = 37L,
        enabled = true,
        schemaVersion = "1.1.0",
        sections = listOf(
            DiagnosticsReport.Section(
                "App",
                listOf(DiagnosticsReport.Row("Version", "1.36 (37)"))
            )
        ),
        events = events.toList(),
        rawEvents = emptyList()
    )

    // ------------------------------------------------------------------- csv

    @Test
    fun `csv starts with the fixed columns and then every property as a column`() {
        val csv = DiagnosticsReport.renderCsv(
            report(
                event("app_launch_result", mapOf("package" to "com.a", "outcome" to "success")),
                event("search_performed", mapOf("query_length" to "4"))
            )
        )
        val lines = csv.trim().split("\n")
        val header = lines[0].split(",")
        assertEquals("timestamp", header[0].trim('"'))
        assertEquals("log_level", header[1].trim('"'))
        assertEquals("category", header[2].trim('"'))
        assertEquals("event_name", header[3].trim('"'))
        assertTrue(header.contains("\"package\""))
        assertTrue(header.contains("\"query_length\""))
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("\"app_launch_result\""))
        assertTrue(lines[1].contains("\"activity\""))
    }

    @Test
    fun `csv keeps an awkward value inside one field`() {
        val csv = DiagnosticsReport.renderCsv(
            report(event("settings_changed", mapOf("value" to "a,b\"c\nd")))
        )
        // The comma, the quote and the newline all survive inside one quoted
        // field, which is what RFC 4180 asks for — so a parser reads one row
        // even though the text now spans three physical lines.
        assertTrue(csv.contains("\"a,b\"\"c\nd\""))
        assertEquals(3, csv.trim().split("\n").size)
    }

    // ------------------------------------------------------------------ json

    @Test
    fun `json carries the envelope, the sections and the events`() {
        val json = DiagnosticsReport.renderJson(
            report(event("bubble_hidden", mapOf("reason" to "sensitive_app")))
        )
        assertTrue(json.contains("\"schemaVersion\": \"1.1.0\""))
        assertTrue(json.contains("\"internetPermission\": false"))
        assertTrue(json.contains("\"title\": \"App\""))
        assertTrue(json.contains("bubble_hidden"))
        assertTrue(json.contains("sensitive_app"))
    }

    @Test
    fun `json escapes a value that would otherwise break the document`() {
        val json = DiagnosticsReport.renderJson(
            report(event("settings_changed", mapOf("value" to "a\"b\\c")))
        )
        assertTrue(json.contains("a\\\"b\\\\c"))
    }

    // ------------------------------------------------------------------- zip

    @Test
    fun `the zip holds all four reports and each matches its renderer`() {
        val r = report(
            event("screen_view", mapOf("screen_name" to "Home")),
            event("crash", mapOf("fatal" to "true"), category = "error", level = "fatal")
        )
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(DiagnosticsReport.renderZip(r))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }

        assertEquals(
            setOf(
                "lowdistraction-diagnostics.txt",
                "lowdistraction-diagnostics.md",
                "lowdistraction-diagnostics.json",
                "lowdistraction-diagnostics.csv"
            ),
            entries.keys
        )
        assertEquals(
            DiagnosticsReport.renderText(r),
            entries["lowdistraction-diagnostics.txt"]
        )
        assertEquals(
            DiagnosticsReport.renderMarkdown(r),
            entries["lowdistraction-diagnostics.md"]
        )
        assertEquals(
            DiagnosticsReport.renderJson(r),
            entries["lowdistraction-diagnostics.json"]
        )
        assertEquals(
            DiagnosticsReport.renderCsv(r),
            entries["lowdistraction-diagnostics.csv"]
        )
    }

    @Test
    fun `a zip with nothing in the log is still a valid zip`() {
        val bytes = DiagnosticsReport.renderZip(report())
        assertTrue(bytes.size > 0)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            assertEquals("lowdistraction-diagnostics.txt", zip.nextEntry?.name)
        }
    }

    // -------------------------------------------------------------- log health

    @Test
    fun `an intent with no outcome is called out`() {
        val rows = DiagnosticsReport.healthRows(
            listOf(
                event("access_fix_requested", mapOf("attempt_id" to "att_1")),
                event("access_fix_requested", mapOf("attempt_id" to "att_2")),
                event("access_fix_requested", mapOf("attempt_id" to "att_3")),
                event("access_fix_result", mapOf("attempt_id" to "att_1"))
            )
        )
        val byKey = rows.associate { it.key to it.value }
        assertEquals("3", byKey["Permission fix intents"])
        assertEquals("1", byKey["Permission fix outcomes"])
        assertEquals("2", byKey["Permission fix unmatched"])
    }

    @Test
    fun `matching intents and outcomes are reported as clean`() {
        val rows = DiagnosticsReport.healthRows(
            listOf(
                event("app_launch_requested", mapOf("attempt_id" to "att_1")),
                event("app_launch_result", mapOf("attempt_id" to "att_1"))
            )
        )
        val byKey = rows.associate { it.key to it.value }
        assertEquals("1", byKey["App launch intents"])
        assertEquals("1", byKey["App launch outcomes"])
        assertTrue(byKey["App launch unmatched"]!!.startsWith("0"))
    }

    @Test
    fun `more outcomes than intents never reports a negative`() {
        val rows = DiagnosticsReport.healthRows(
            listOf(
                event("app_launch_result", mapOf("attempt_id" to "att_1")),
                event("app_launch_result", mapOf("attempt_id" to "att_2"))
            )
        )
        val byKey = rows.associate { it.key to it.value }
        assertTrue(byKey["App launch unmatched"]!!.startsWith("0"))
    }

    // ----------------------------------------------------------------- signals

    @Test
    fun `signals count the friction the standard asks for`() {
        val rows = DiagnosticsReport.signalRows(
            listOf(
                event("rage_tap"), event("rage_tap"), event("dead_click"),
                event("permission_loop"),
                event("scroll_depth", mapOf("percent" to "75")),
                event("scroll_depth", mapOf("percent" to "100")),
                event("time_to_first_value", mapOf("duration_ms" to "820")),
                event("app_launch_result", mapOf("outcome" to "success")),
                event("app_launch_result", mapOf("outcome" to "cancelled"))
            )
        )
        val byKey = rows.associate { it.key to it.value }
        assertEquals("2", byKey["Rage taps"])
        assertEquals("1", byKey["Dead clicks"])
        assertEquals("1", byKey["Permission loops"])
        assertEquals("100%", byKey["Deepest scroll"])
        assertEquals("820 ms", byKey["Time to first action"])
        assertEquals("1", byKey["Apps opened"])
        assertEquals("1", byKey["Stopped at the pause"])
    }

    @Test
    fun `signals with an empty log say so rather than guessing`() {
        val byKey = DiagnosticsReport.signalRows(emptyList()).associate { it.key to it.value }
        assertEquals("0", byKey["Rage taps"])
        assertEquals("—", byKey["Deepest scroll"])
        assertEquals("—", byKey["Time to first action"])
    }

    // ------------------------------------------------------------------ basics

    @Test
    fun `crashes and sessions are counted from the events`() {
        val r = report(
            event("session_start"),
            event("crash", level = "fatal"),
            event("crash", level = "fatal")
        )
        assertEquals(2, r.crashCount)
        assertEquals(1, r.sessions)
    }

    @Test
    fun `a text report names the version and says when nothing was recorded`() {
        val text = DiagnosticsReport.renderText(report())
        assertTrue(text.contains("1.36"))
        assertTrue(text.contains("nothing recorded yet"))
    }
}
