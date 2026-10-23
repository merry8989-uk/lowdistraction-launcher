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

    enum class ExportFormat(val value: String) {
        TXT("txt"),
        MD("md"),
        JSON("json"),
        CSV("csv"),
        /** All four shapes in one archive. */
        ZIP("zip"),
        /** Everything at once, attached separately — what the mail does by default. */
        ALL("all")
    }

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

    /**
     * Which kind of log an event is. The twelve categories the shared standard
     * lists are mapped onto what this app can actually produce — see
     * docs/logging.md for the mapping and for what does not apply here.
     */
    enum class Category(val value: String) {
        APPLICATION("application"),
        ACTIVITY("activity"),
        ERROR("error"),
        SECURITY("security"),
        PERFORMANCE("performance"),
        AUDIT("audit"),
        BACKGROUND("background"),
        DEBUG("debug")
    }

    private fun log(
        c: Context,
        name: String,
        level: String,
        category: String,
        vararg props: Pair<String, Any?>
    ) {
        Diag.event(c, name, level, category, props.toList())
    }

    // -------------------------------------------------------------- session
    fun sessionStart(c: Context, startType: StartType, coldMs: Long, warmMs: Long) =
        log(
            c, "session_start", "INFO", Category.APPLICATION.value,
            "start_type" to startType.value,
            "cold_start_ms" to (if (coldMs > 0) coldMs else null),
            "warm_start_ms" to (if (warmMs > 0) warmMs else null)
        )

    fun dropOff(c: Context, lastScreen: String, lastEvent: String, durationMs: Long) =
        log(
            c, "drop_off", "INFO", Category.ACTIVITY.value,
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
            c, "screen_view", "INFO", Category.ACTIVITY.value,
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
        c, "permission_state_resolved", "INFO", Category.SECURITY.value,
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
            c, "access_fix_requested", "INFO", Category.SECURITY.value,
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
        c, "access_fix_result", "INFO", Category.SECURITY.value,
        "attempt_id" to attemptId,
        "action_taken" to actionTaken,
        "state_after" to after.value,
        "outcome" to outcome.value,
        "error_code" to errorCode
    )

    fun permissionLoop(c: Context, permission: String, attempts: Int) =
        log(
            c, "permission_loop", "WARN", Category.SECURITY.value,
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
        c, "media_query_result", "INFO", Category.PERFORMANCE.value,
        "source" to source.value,
        "item_count" to itemCount,
        "query_ms" to queryMs,
        "folders_visible" to folders
    )

    fun appListLoaded(c: Context, itemCount: Int, queryMs: Long, hidden: Int, sortMode: String) =
        log(
            c, "app_list_loaded", "INFO", Category.PERFORMANCE.value,
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
        c, "app_launch_requested", "INFO", Category.ACTIVITY.value,
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
        c, "app_launch_result", "INFO", Category.ACTIVITY.value,
        "package" to pkg,
        "attempt_id" to attemptId,
        "outcome" to outcome.value,
        "waited_ms" to waitedMs
    )

    // ---------------------------------------------------------------- search
    /** Only the length of the query is recorded — never the query itself. */
    fun searchPerformed(c: Context, queryLength: Int, resultCount: Int, source: SearchSource) =
        log(
            c, "search_performed", "INFO", Category.ACTIVITY.value,
            "query_length" to queryLength,
            "result_count" to resultCount,
            "source" to source.value
        )

    fun searchNoResults(c: Context, queryLength: Int, source: SearchSource) =
        log(
            c, "search_no_results", "INFO", Category.ACTIVITY.value,
            "query_length" to queryLength,
            "source" to source.value
        )

    // -------------------------------------------------------------- settings
    fun settingsChanged(c: Context, setting: String, value: String?) =
        log(
            c, "settings_changed", "INFO", Category.AUDIT.value,
            "setting" to setting,
            "value" to value
        )

    fun diagnosticsToggled(c: Context, enabled: Boolean) =
        log(
            c, "diagnostics_action", "INFO", Category.AUDIT.value,
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
        c, "diagnostics_action", "INFO", Category.AUDIT.value,
        "action" to action.value,
        "format" to format?.value,
        "channel" to channel?.value,
        "bytes" to bytes,
        "entries" to entries
    )

    fun bubbleToggled(c: Context, enabled: Boolean) =
        log(c, "bubble_toggled", "INFO", Category.ACTIVITY.value, "enabled" to enabled)

    // ----------------------------------------------------------------- guide
    fun guideStep(c: Context, step: Int, total: Int) =
        log(c, "guide_step", "INFO", Category.ACTIVITY.value, "step" to step, "total" to total)

    fun guideFinished(c: Context, stepsSeen: Int, skipped: Boolean) =
        log(c, "guide_finished", "INFO", Category.ACTIVITY.value, "steps_seen" to stepsSeen, "skipped" to skipped)

    fun teaMode(c: Context, action: TeaAction, durationMs: Long?) =
        log(
            c, "tea_mode", "INFO", Category.ACTIVITY.value,
            "action" to action.value,
            "duration_ms" to durationMs
        )

    // ------------------------------------------------------------ health
    fun performanceMarker(c: Context, flow: String, durationMs: Long) =
        log(c, "performance_marker", "INFO", Category.PERFORMANCE.value, "flow" to flow, "duration_ms" to durationMs)

    fun errorStateShown(c: Context, errorCode: String, screen: String, messageKey: String?) =
        log(
            c, "error_state_shown", "WARN", Category.ERROR.value,
            "error_code" to errorCode,
            "screen" to screen,
            "message_key" to messageKey
        )

    fun crash(
        c: Context,
        exceptionType: String,
        stackHash: String,
        stackTrace: String,
        lastScreen: String,
        lastAction: String,
        thread: String,
        mainThread: Boolean,
        uptimeMs: Long
    ) = log(
        c, "crash", "FATAL", Category.ERROR.value,
        "exception_type" to exceptionType,
        "stack_trace_hash" to stackHash,
        // The trace itself, not only a hash of it: a hash says two crashes match,
        // it does not say where. Redacted and capped by Diag.
        "stack_trace" to stackTrace,
        "last_screen" to lastScreen,
        "last_action" to lastAction,
        "thread" to thread,
        "main_thread" to mainThread,
        "uptime_ms" to uptimeMs
    )

    // --------------------------------------------------------- crash reporting
    /**
     * A crash from the previous run, recorded once the app is up again — the only
     * way a crash loop becomes visible from inside the app.
     */
    fun previousRunCrashed(
        c: Context,
        exceptionType: String,
        lastScreen: String,
        occurredAt: String
    ) = log(
        c, "previous_run_crashed", "WARN", Category.ERROR.value,
        "exception_type" to exceptionType,
        "last_screen" to lastScreen,
        "occurred_at" to occurredAt
    )

    fun anr(c: Context, durationMs: Long, screen: String) =
        log(c, "anr", "WARN", Category.ERROR.value, "duration_ms" to durationMs, "last_screen" to screen)

    // ------------------------------------------------------------- signals
    fun rageTap(c: Context, target: String, taps: Int, windowMs: Long) =
        log(
            c, "rage_tap", "WARN", Category.ACTIVITY.value,
            "target" to target,
            "taps" to taps,
            "window_ms" to windowMs
        )

    fun deadClick(c: Context, target: String) =
        log(c, "dead_click", "INFO", Category.ACTIVITY.value, "target" to target)

    fun backButtonSpam(c: Context, presses: Int, windowMs: Long, screen: String) =
        log(
            c, "back_button_spam", "WARN", Category.ACTIVITY.value,
            "presses" to presses,
            "window_ms" to windowMs,
            "screen" to screen
        )

    fun scrollDepth(c: Context, percent: Int, screen: String) =
        log(c, "scroll_depth", "INFO", Category.ACTIVITY.value, "percent" to percent, "screen" to screen)

    fun timeToFirstValue(c: Context, flow: String, durationMs: Long) =
        log(c, "time_to_first_value", "INFO", Category.PERFORMANCE.value, "flow" to flow, "duration_ms" to durationMs)

    // ----------------------------------------------------------- background
    fun fileIndexRebuild(
        c: Context,
        outcome: Outcome,
        itemCount: Int?,
        durationMs: Long?,
        trigger: String?
    ) = log(
        c, "file_index_rebuild", "INFO", Category.BACKGROUND.value,
        "outcome" to outcome.value,
        "item_count" to itemCount,
        "duration_ms" to durationMs,
        "trigger" to trigger
    )

    // ------------------------------------------------------------------ debug
    fun bubbleHidden(c: Context, reason: String) =
        log(c, "bubble_hidden", "DEBUG", Category.DEBUG.value, "reason" to reason)

    // ------------------------------------------------------------- adoption
    fun wheelOpened(c: Context, appCount: Int) =
        log(c, "wheel_opened", "INFO", Category.ACTIVITY.value, "app_count" to appCount)

    // ------------------------------------------------- handled exceptions
    /**
     * An exception that was caught and handled rather than crashing the app.
     *
     * The plan names `captureException` as part of the SDK surface, and it was
     * the real gap here: uncaught exceptions were recorded, but every failure
     * the app swallowed went unmentioned.
     */
    fun exception(
        c: Context,
        exceptionType: String,
        operation: String,
        outcome: Outcome,
        errorCode: String?,
        stackHash: String?
    ) = log(
        c, "exception", "ERROR", Category.ERROR.value,
        "exception_type" to exceptionType,
        "operation" to operation,
        "outcome" to outcome.value,
        "error_code" to errorCode,
        "stack_trace_hash" to stackHash
    )
}
