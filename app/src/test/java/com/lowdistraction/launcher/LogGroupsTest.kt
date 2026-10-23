package com.lowdistraction.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a property lands in the nested record.
 *
 * The table itself is checked against the schema by `tools/validate_logging.py`
 * on every build; these tests pin the behaviour that table drives, so a property
 * filed in the wrong place is caught here too.
 */
class LogGroupsTest {

    @Test
    fun `an error property goes to the error group`() {
        assertEquals(LogGroups.ERROR, LogGroups.of("stack_trace"))
        assertEquals(LogGroups.ERROR, LogGroups.of("exception_type"))
        assertEquals(LogGroups.ERROR, LogGroups.of("error_code"))
    }

    @Test
    fun `a measured quantity goes to metrics`() {
        assertEquals(LogGroups.METRICS, LogGroups.of("duration_ms"))
        assertEquals(LogGroups.METRICS, LogGroups.of("uptime_ms"))
        assertEquals(LogGroups.METRICS, LogGroups.of("query_ms"))
    }

    @Test
    fun `the correlation id goes to correlation, not context`() {
        assertEquals(LogGroups.CORRELATION, LogGroups.of("attempt_id"))
    }

    @Test
    fun `a descriptive property goes to context`() {
        assertEquals(LogGroups.CONTEXT, LogGroups.of("screen"))
        assertEquals(LogGroups.CONTEXT, LogGroups.of("outcome"))
        assertEquals(LogGroups.CONTEXT, LogGroups.of("package"))
    }

    @Test
    fun `an unknown property lands in attributes rather than being dropped`() {
        assertEquals(LogGroups.ATTRIBUTES, LogGroups.of("something_new"))
        assertEquals(LogGroups.ATTRIBUTES, LogGroups.of(""))
    }

    @Test
    fun `no property is placed twice`() {
        // The map is a map, so a duplicate key would have silently overwritten.
        val known = LogGroups.known()
        assertEquals(known.size, known.keys.size)
        assertTrue(known.isNotEmpty())
    }

    @Test
    fun `the groups used are exactly the ones the schema declares`() {
        val used = LogGroups.known().values.toSet()
        assertEquals(
            setOf(
                LogGroups.CONTEXT, LogGroups.CORRELATION,
                LogGroups.ERROR, LogGroups.METRICS
            ),
            used
        )
    }
}
