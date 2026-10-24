package com.lowdistraction.launcher

/**
 * The log explorer's brain: which events match, and how they add up.
 *
 * Deliberately free of Android, so the filtering and the aggregation can be
 * unit-tested on the JVM. The screen in [LogExplorerActivity] only draws what
 * this decides.
 */
object LogFilter {

    /** The "no filter" value for a single-select filter. */
    const val ANY = "ALL"

    /** In the order they are offered, worst last. */
    val SEVERITIES = listOf("ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")

    /** The categories this app can produce, in the report's order. */
    val CATEGORIES = listOf(
        "ALL", "application", "activity", "error", "security",
        "performance", "audit", "background", "debug"
    )

    /** What the user has narrowed the log down to. */
    data class Query(
        val severity: String = ANY,
        val category: String = ANY,
        val text: String = ""
    ) {
        val isFiltered: Boolean
            get() = severity != ANY || category != ANY || text.isNotBlank()
    }

    /** The events that match, newest first — the order a log is read in. */
    fun apply(events: List<Diag.Event>, query: Query): List<Diag.Event> =
        events.asReversed().filter { matches(it, query) }

    private fun matches(e: Diag.Event, query: Query): Boolean {
        if (query.severity != ANY && e.level != query.severity) return false
        if (query.category != ANY && e.category != query.category) return false
        val needle = query.text.trim().lowercase()
        if (needle.isEmpty()) return true
        return contains(e, needle)
    }

    /**
     * The search box looks at everything the event says: its name, its category
     * and severity, the screen, and every property — both the key and the value,
     * so `package` finds the property and `com.whatsapp` finds the app.
     */
    private fun contains(e: Diag.Event, needle: String): Boolean {
        if (e.name.lowercase().contains(needle)) return true
        if (e.category.lowercase().contains(needle)) return true
        if (e.level.lowercase().contains(needle)) return true
        if (e.screen.lowercase().contains(needle)) return true
        if (e.session.lowercase().contains(needle)) return true
        for ((key, value) in e.props) {
            if (key.lowercase().contains(needle)) return true
            if (value.lowercase().contains(needle)) return true
        }
        return false
    }

    /**
     * How many events carry each severity. Every level is listed even at zero:
     * "no errors" is an answer, and hiding it is not.
     */
    fun bySeverity(events: List<Diag.Event>): List<Pair<String, Int>> =
        SEVERITIES.drop(1).map { level -> level to events.count { it.level == level } }

    /** How many events carry each category, in the report's order. */
    fun byCategory(events: List<Diag.Event>): List<Pair<String, Int>> =
        CATEGORIES.drop(1).map { category -> category to events.count { it.category == category } }

    /**
     * One line saying what is being shown and what is being left out, so a
     * screenshot of the screen cannot be mistaken for the whole log.
     */
    fun summary(shown: Int, total: Int, query: Query): String {
        val parts = ArrayList<String>()
        if (query.severity != ANY) parts.add(query.severity)
        if (query.category != ANY) parts.add(query.category)
        if (query.text.isNotBlank()) parts.add("\"" + query.text.trim() + "\"")
        val filter = if (parts.isEmpty()) "no filter" else parts.joinToString(" + ")
        val tail = if (shown == total) "$total events" else "$shown of $total events"
        return tail + "  ·  " + filter
    }

    /** Where a severity sits in the order, for sorting and for colouring. */
    fun rank(level: String): Int = SEVERITIES.indexOf(level).coerceAtLeast(0)

    /** The most recent event's time, or 0 for an empty log. */
    fun newest(events: List<Diag.Event>): Long = events.maxOfOrNull { it.time } ?: 0L
}
