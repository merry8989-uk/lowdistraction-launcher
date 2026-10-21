package com.lowdistraction.launcher

import android.content.Context

/**
 * Every event this app records, in one place.
 *
 * This is deliberate: `tools/validate_logging.py` reads this file and checks it
 * against `tools/logging_schema.json` in CI, so an event that is not in the
 * schema — or a property the schema does not allow — fails the build instead of
 * shipping quietly. Nothing outside this file may call [Diag.event].
 *
 * Naming follows the shared standard: `object_action` in snake_case, properties
 * in snake_case, and fixed enums rather than free text. Values that carry
 * personal data are never passed in at all; [Diag] redacts what it can.
 */
object Events {

    // ------------------------------------------------------------ fixed enums
    // Kept in step with the "enums" block of tools/logging_schema.json.
    enum class StartType(val value: String) { COLD("cold"), WARM("warm") }

    enum class PermState(val value: String) {
        GRANTED("granted"),
        PARTIAL("partial"),
        DENIED("denied"),
        PERMANENTLY_DENIED("permanently_denied"),
        NOT_APPLICABLE("not_applicable")
    }

    enum class Outcome(val value: String) {
        SUCCESS("success"),
        NO_OP("no_op"),
        FAILED("failed"),
        CANCELLED("cancelled")
    }

    enum class LaunchSource(val value: String) {
        LIST("list"),
        PINNED("pinned"),
        WHEEL("wheel"),
        SEARCH("search"),
        HIDDEN("hidden")
    }

    enum class SearchSource(val value: String) {
        APPS("apps"),
        SETTINGS("settings"),
        CONTACTS("contacts"),
        CALENDAR("calendar"),
        FILES("files"),
        DEVCHECK("devcheck")
    }

    enum class ExportFormat(val value: String) { TXT("txt"), MD("md"), JSON("json"), CSV("csv") }

    enum class ExportChannel(val value: String) { SAVE("save"), SHARE("share"), EMAIL("email") }

    enum class DiagAction(val value: String) {
        EXPORT("export"),
        SHARE("share"),
        EMAIL("email"),
        CLEAN("clean"),
        REFRESH("refresh")
    }

    enum class TeaAction(val value: String) {
        START("start"),
        END("end"),
        ENDED_EARLY("ended_early")
    }

    private fun log(c: Context, name: String, level: String, vararg props: Pair<String, Any?>) {
        Diag.event(c, name, level, props.toList())
    }

    // -------------------------------------------------------------- session
    fun sessionStart(c: Context, startType: StartType, coldMs: Long, warmMs: Long) =
        log(
            c, "session_start", "info",
            "start_type" to startType.value,
            "cold_start_ms" to (if (coldMs > 0) coldMs else null),
            "warm_start_ms" to (if (warmMs > 0) warmMs else null)
        )

    fun dropOff(c: Context, lastScreen: String, lastEvent: String, durationMs: Long) =
        log(
            c, "drop_off", "info",
            "last_screen" to lastScreen,
            "last_event" to lastEvent,
            "duration_ms" to durationMs
        )

    /**
     * Records a screen being shown, filling in the screen we came from and how
     * long that one was up. Call it from `onResume`, one line per screen.
     */
    fun screenView(c: Context, name: String) {
        val previous = Diag.currentScreen()
        val duration = Diag.timeOnScreenMs()
        Diag.setScreen(name)
        log(
            c, "screen_view", "info",
            "screen_name" to name,
            "previous_screen" to previous,
            "duration_ms" to duration
        )
    }

    // ---------------------------------------------------------- permissions
    fun permissionStateResolved(
        c: Context,
        permission: String,
        state: PermState,
        itemCount: Int?
    ) = log(
        c, "permission_state_resolved", "info",
        "permission" to permission,
        "state" to state.value,
        "item_count" to itemCount
    )

    /**
     * The intent half of a fix. Bumps the per-session counter and raises a
     * `permission_loop` warning once the same fix has been asked for three
     * times — the exact loop the shared standard was written to catch.
     */
    fun accessFixRequested(
        c: Context,
        permission: String,
        before: PermState,
        attemptId: String,
        actionTaken: String?
    ) {
        val attempts = Diag.bumpFixAttempt(permission)
        log(
            c, "access_fix_requested", "info",
            "permission" to permission,
            "state_before" to before.value,
            "attempt_id" to attemptId,
            "action_taken" to actionTaken
        )
        if (attempts >= 3) permissionLoop(c, permission, attempts)
    }

    fun accessFixResult(
        c: Context,
        attemptId: String,
        actionTaken: String?,
        after: PermState,
        outcome: Outcome,
        errorCode: String?
    ) = log(
        c, "access_fix_result", "info",
        "attempt_id" to attemptId,
        "action_taken" to actionTaken,
        "state_after" to after.value,
        "outcome" to outcome.value,
        "error_code" to errorCode
    )

    fun permissionLoop(c: Context, permission: String, attempts: Int) =
        log(
            c, "permission_loop", "warn",
            "permission" to permission,
            "attempts" to attempts
        )

    // ------------------------------------------------------------ the list
    fun mediaQueryResult(
        c: Context,
        source: SearchSource,
        itemCount: Int,
        queryMs: Long,
        folders: Int?
    ) = log(
        c, "media_query_result", "info",
        "source" to source.value,
        "item_count" to itemCount,
        "query_ms" to queryMs,
        "folders_visible" to folders
    )

    fun appListLoaded(c: Context, itemCount: Int, queryMs: Long, hidden: Int, sortMode: String) =
        log(
            c, "app_list_loaded", "info",
            "item_count" to itemCount,
            "query_ms" to queryMs,
            "hidden_count" to hidden,
            "sort_mode" to sortMode
        )

    // ---------------------------------------------------------------- launch
    fun appLaunchRequested(
        c: Context,
        pkg: String,
        source: LaunchSource,
        pauseEnabled: Boolean,
        attemptId: String
    ) = log(
        c, "app_launch_requested", "info",
        "package" to pkg,
        "source" to source.value,
        "pause_enabled" to pauseEnabled,
        "attempt_id" to attemptId
    )

    fun appLaunchResult(
        c: Context,
        pkg: String,
        attemptId: String?,
        outcome: Outcome,
        waitedMs: Long
    ) = log(
        c, "app_launch_result", "info",
        "package" to pkg,
        "attempt_id" to attemptId,
        "outcome" to outcome.value,
        "waited_ms" to waitedMs
    )

    // ---------------------------------------------------------------- search
    /** Only the length of the query is recorded — never the query itself. */
    fun searchPerformed(c: Context, queryLength: Int, resultCount: Int, source: SearchSource) =
        log(
            c, "search_performed", "info",
            "query_length" to queryLength,
            "result_count" to resultCount,
            "source" to source.value
        )

    fun searchNoResults(c: Context, queryLength: Int, source: SearchSource) =
        log(
            c, "search_no_results", "info",
            "query_length" to queryLength,
            "source" to source.value
        )

    // -------------------------------------------------------------- settings
    fun settingsChanged(c: Context, setting: String, value: String?) =
        log(
            c, "settings_changed", "info",
            "setting" to setting,
            "value" to value
        )

    fun diagnosticsToggled(c: Context, enabled: Boolean) =
        log(
            c, "diagnostics_action", "info",
            "action" to "toggle",
            "value" to enabled
        )

    fun diagnosticsAction(
        c: Context,
        action: DiagAction,
        format: ExportFormat?,
        channel: ExportChannel?,
        bytes: Long?,
        entries: Int?
    ) = log(
        c, "diagnostics_action", "info",
        "action" to action.value,
        "format" to format?.value,
        "channel" to channel?.value,
        "bytes" to bytes,
        "entries" to entries
    )

    fun bubbleToggled(c: Context, enabled: Boolean) =
        log(c, "bubble_toggled", "info", "enabled" to enabled)

    // ----------------------------------------------------------------- guide
    fun guideStep(c: Context, step: Int, total: Int) =
        log(c, "guide_step", "info", "step" to step, "total" to total)

    fun guideFinished(c: Context, stepsSeen: Int, skipped: Boolean) =
        log(c, "guide_finished", "info", "steps_seen" to stepsSeen, "skipped" to skipped)

    fun teaMode(c: Context, action: TeaAction, durationMs: Long?) =
        log(
            c, "tea_mode", "info",
            "action" to action.value,
            "duration_ms" to durationMs
        )

    // ------------------------------------------------------------ health
    fun performanceMarker(c: Context, flow: String, durationMs: Long) =
        log(c, "performance_marker", "info", "flow" to flow, "duration_ms" to durationMs)

    fun errorStateShown(c: Context, errorCode: String, screen: String, messageKey: String?) =
        log(
            c, "error_state_shown", "warn",
            "error_code" to errorCode,
            "screen" to screen,
            "message_key" to messageKey
        )

    fun crash(
        c: Context,
        exceptionType: String,
        stackHash: String,
        lastScreen: String,
        lastAction: String,
        thread: String
    ) = log(
        c, "crash", "error",
        "fatal" to true,
        "exception_type" to exceptionType,
        "stack_trace_hash" to stackHash,
        "last_screen" to lastScreen,
        "last_action" to lastAction,
        "thread" to thread
    )

    fun anr(c: Context, durationMs: Long, screen: String) =
        log(c, "anr", "warn", "duration_ms" to durationMs, "last_screen" to screen)

    // ------------------------------------------------------------- signals
    fun rageTap(c: Context, target: String, taps: Int, windowMs: Long) =
        log(
            c, "rage_tap", "warn",
            "target" to target,
            "taps" to taps,
            "window_ms" to windowMs
        )

    fun deadClick(c: Context, target: String) =
        log(c, "dead_click", "info", "target" to target)

    fun backButtonSpam(c: Context, presses: Int, windowMs: Long, screen: String) =
        log(
            c, "back_button_spam", "warn",
            "presses" to presses,
            "window_ms" to windowMs,
            "screen" to screen
        )

    fun scrollDepth(c: Context, percent: Int, screen: String) =
        log(c, "scroll_depth", "info", "percent" to percent, "screen" to screen)

    fun timeToFirstValue(c: Context, flow: String, durationMs: Long) =
        log(c, "time_to_first_value", "info", "flow" to flow, "duration_ms" to durationMs)
}
