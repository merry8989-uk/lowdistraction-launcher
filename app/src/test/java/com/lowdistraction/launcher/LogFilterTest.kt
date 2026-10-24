package com.lowdistraction.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log explorer's filtering, searching and counting.
 *
 * Pure logic, so it is tested here rather than by tapping at a screen.
 */
class LogFilterTest {

    private fun event(
        name: String,
        level: String = "INFO",
        category: String = "activity",
        screen: String = "Home",
        session: String = "sess_a",
        props: Map<String, String> = emptyMap(),
        time: Long = 1_700_000_000_000L
    ) = Diag.Event(
        name = name, time = time, level = level, category = category,
        screen = screen, session = session, props = props
    )

    private val log = listOf(
        event("screen_view", props = mapOf("screen_name" to "Home"), time = 1000L),
        event("app_launch_result", props = mapOf("package" to "com.whatsapp", "outcome" to "success"), time = 2000L),
        event("crash", level = "FATAL", category = "error", time = 3000L),
        event("rage_tap", level = "WARN", time = 4000L),
        event("settings_changed", category = "audit", screen = "Settings",
            props = mapOf("setting" to "timer_length"), time = 5000L)
    )

    // --------------------------------------------------------------- filtering

    @Test
    fun `no filter keeps everything, newest first`() {
        val shown = LogFilter.apply(log, LogFilter.Query())
        assertEquals(5, shown.size)
        assertEquals(5000L, shown.first().time)
        assertEquals(1000L, shown.last().time)
    }

    @Test
    fun `a severity filter keeps only that severity`() {
        val shown = LogFilter.apply(log, LogFilter.Query(severity = "FATAL"))
        assertEquals(1, shown.size)
        assertEquals("crash", shown[0].name)
    }

    @Test
    fun `a category filter keeps only that category`() {
        val shown = LogFilter.apply(log, LogFilter.Query(category = "audit"))
        assertEquals(1, shown.size)
        assertEquals("settings_changed", shown[0].name)
    }

    @Test
    fun `two filters are both applied`() {
        val none = LogFilter.apply(log, LogFilter.Query(severity = "FATAL", category = "audit"))
        assertEquals(0, none.size)
        val one = LogFilter.apply(log, LogFilter.Query(severity = "FATAL", category = "error"))
        assertEquals(1, one.size)
    }

    @Test
    fun `the ALL value means no filter on that axis`() {
        val shown = LogFilter.apply(log, LogFilter.Query(severity = LogFilter.ANY))
        assertEquals(5, shown.size)
    }

    // ---------------------------------------------------------------- searching

    @Test
    fun `search matches the event name`() {
        val shown = LogFilter.apply(log, LogFilter.Query(text = "rage"))
        assertEquals(1, shown.size)
        assertEquals("rage_tap", shown[0].name)
    }

    @Test
    fun `search matches a property key`() {
        val shown = LogFilter.apply(log, LogFilter.Query(text = "package"))
        assertEquals(1, shown.size)
        assertEquals("app_launch_result", shown[0].name)
    }

    @Test
    fun `search matches a property value, so an app can be found by name`() {
        val shown = LogFilter.apply(log, LogFilter.Query(text = "com.whatsapp"))
        assertEquals(1, shown.size)
        assertEquals("app_launch_result", shown[0].name)
    }

    @Test
    fun `search matches the category, the severity, the screen and the session`() {
        assertEquals(1, LogFilter.apply(log, LogFilter.Query(text = "audit")).size)
        assertEquals(1, LogFilter.apply(log, LogFilter.Query(text = "fatal")).size)
        assertEquals(1, LogFilter.apply(log, LogFilter.Query(text = "settings")).size)
        assertEquals(5, LogFilter.apply(log, LogFilter.Query(text = "sess_a")).size)
    }

    @Test
    fun `search ignores case and surrounding space`() {
        assertEquals(1, LogFilter.apply(log, LogFilter.Query(text = "  WHATSAPP  ")).size)
    }

    @Test
    fun `a search that matches nothing returns nothing rather than everything`() {
        assertEquals(0, LogFilter.apply(log, LogFilter.Query(text = "zzzz")).size)
    }

    @Test
    fun `whitespace alone is not a filter`() {
        assertEquals(5, LogFilter.apply(log, LogFilter.Query(text = "   ")).size)
        assertFalse(LogFilter.Query(text = "   ").isFiltered)
    }

    @Test
    fun `search and a severity filter combine`() {
        val shown = LogFilter.apply(
            log, LogFilter.Query(severity = "WARN", text = "rage")
        )
        assertEquals(1, shown.size)
        assertEquals(0, LogFilter.apply(log, LogFilter.Query(severity = "INFO", text = "rage")).size)
    }

    // -------------------------------------------------------------- aggregation

    @Test
    fun `every severity is counted, including the ones at zero`() {
        val counts = LogFilter.bySeverity(log).toMap()
        assertEquals(3, counts["INFO"])
        assertEquals(1, counts["WARN"])
        assertEquals(1, counts["FATAL"])
        assertEquals(0, counts["TRACE"])
        assertEquals(0, counts["DEBUG"])
        assertEquals(0, counts["ERROR"])
    }

    @Test
    fun `every category is counted, including the ones at zero`() {
        val counts = LogFilter.byCategory(log).toMap()
        assertEquals(3, counts["activity"])
        assertEquals(1, counts["error"])
        assertEquals(1, counts["audit"])
        assertEquals(0, counts["background"])
    }

    @Test
    fun `the aggregation covers the whole log, not the filtered view`() {
        val counts = LogFilter.bySeverity(log).toMap()
        assertEquals(1, counts["FATAL"])
    }

    // ------------------------------------------------------------------ summary

    @Test
    fun `the summary says nothing is filtered out when nothing is`() {
        assertEquals("5 events  ·  no filter", LogFilter.summary(5, 5, LogFilter.Query()))
    }

    @Test
    fun `the summary says how much is being left out`() {
        val line = LogFilter.summary(1, 5, LogFilter.Query(severity = "FATAL"))
        assertEquals("1 of 5 events  ·  FATAL", line)
    }

    @Test
    fun `the summary names every active filter`() {
        val line = LogFilter.summary(
            0, 5, LogFilter.Query(severity = "ERROR", category = "error", text = " com.x ")
        )
        assertEquals("0 of 5 events  ·  ERROR + error + \"com.x\"", line)
    }

    // -------------------------------------------------------------------- misc

    @Test
    fun `a query knows whether it filters anything`() {
        assertFalse(LogFilter.Query().isFiltered)
        assertTrue(LogFilter.Query(severity = "INFO").isFiltered)
        assertTrue(LogFilter.Query(category = "audit").isFiltered)
        assertTrue(LogFilter.Query(text = "x").isFiltered)
    }

    @Test
    fun `severity ranks in the order they are offered`() {
        assertTrue(LogFilter.rank("FATAL") > LogFilter.rank("ERROR"))
        assertTrue(LogFilter.rank("ERROR") > LogFilter.rank("WARN"))
        assertTrue(LogFilter.rank("WARN") > LogFilter.rank("INFO"))
        assertTrue(LogFilter.rank("INFO") > LogFilter.rank("DEBUG"))
    }

    @Test
    fun `an empty log filters, counts and summarises without complaint`() {
        assertEquals(0, LogFilter.apply(emptyList(), LogFilter.Query()).size)
        val counts = LogFilter.bySeverity(emptyList())
        assertEquals(6, counts.size)
        assertTrue(counts.all { it.second == 0 })
        assertEquals(0L, LogFilter.newest(emptyList()))
        assertEquals("0 events  ·  no filter", LogFilter.summary(0, 0, LogFilter.Query()))
    }

    @Test
    fun `the newest time is the newest event`() {
        assertEquals(5000L, LogFilter.newest(log))
    }
}
