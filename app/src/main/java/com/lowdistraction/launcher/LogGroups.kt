package com.lowdistraction.launcher

/**
 * Where each property lives in the nested event object.
 *
 * The unified diagnostics plan groups an event's data into `context`,
 * `correlation`, `error` and `metrics`, with everything else in `attributes`.
 * Which group a property belongs to is a property of its name — no name appears
 * in two groups — so one table is enough.
 *
 * `tools/validate_logging.py` checks this table against the schema on every
 * build, so the two cannot drift apart.
 */
object LogGroups {

    const val CONTEXT = "context"
    const val CORRELATION = "correlation"
    const val ERROR = "error"
    const val METRICS = "metrics"

    /** Everything the schema does not place in a group lands here. */
    const val ATTRIBUTES = "attributes"

    private val GROUPS: Map<String, String> = mapOf(
        // context
        "action" to CONTEXT,
        "action_taken" to CONTEXT,
        "channel" to CONTEXT,
        "enabled" to CONTEXT,
        "flow" to CONTEXT,
        "format" to CONTEXT,
        "last_action" to CONTEXT,
        "last_event" to CONTEXT,
        "last_screen" to CONTEXT,
        "main_thread" to CONTEXT,
        "message_key" to CONTEXT,
        "occurred_at" to CONTEXT,
        "operation" to CONTEXT,
        "outcome" to CONTEXT,
        "package" to CONTEXT,
        "pause_enabled" to CONTEXT,
        "permission" to CONTEXT,
        "previous_screen" to CONTEXT,
        "query_length" to CONTEXT,
        "reason" to CONTEXT,
        "result_count" to CONTEXT,
        "screen" to CONTEXT,
        "screen_name" to CONTEXT,
        "setting" to CONTEXT,
        "skipped" to CONTEXT,
        "sort_mode" to CONTEXT,
        "source" to CONTEXT,
        "start_type" to CONTEXT,
        "state" to CONTEXT,
        "state_after" to CONTEXT,
        "state_before" to CONTEXT,
        "step" to CONTEXT,
        "steps_seen" to CONTEXT,
        "target" to CONTEXT,
        "thread" to CONTEXT,
        "total" to CONTEXT,
        "trigger" to CONTEXT,
        "value" to CONTEXT,
        // correlation
        "attempt_id" to CORRELATION,
        // error
        "error_code" to ERROR,
        "exception_type" to ERROR,
        "stack_trace" to ERROR,
        "stack_trace_hash" to ERROR,
        // metrics
        "app_count" to METRICS,
        "attempts" to METRICS,
        "bytes" to METRICS,
        "cold_start_ms" to METRICS,
        "duration_ms" to METRICS,
        "entries" to METRICS,
        "folders_visible" to METRICS,
        "hidden_count" to METRICS,
        "item_count" to METRICS,
        "percent" to METRICS,
        "presses" to METRICS,
        "query_ms" to METRICS,
        "taps" to METRICS,
        "uptime_ms" to METRICS,
        "waited_ms" to METRICS,
        "warm_start_ms" to METRICS,
        "window_ms" to METRICS,
    )

    /** The group a property belongs in, or `attributes` when it has no home. */
    fun of(property: String): String = GROUPS[property] ?: ATTRIBUTES

    /** Every property this table places, for the schema check. */
    fun known(): Map<String, String> = GROUPS
}
