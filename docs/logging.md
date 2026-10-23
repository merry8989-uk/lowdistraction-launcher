# Logging & diagnostics

Low Distraction records a **structured event log** so that a problem can be
diagnosed from a report the user sends, instead of from guesswork. The schema is
shared with the ProGallery logging standard, so events from both can be joined
and compared.

Schema version: **1.1.0** · canonical definition: [`tools/logging_schema.json`](../tools/logging_schema.json)

Version 1.1.0 added the six severity levels, a **category** on every event, and
`app_id` in the envelope.

## The one rule

> A log must capture **intent, outcome and context together**, not intent alone.

That rule is why every event carries the common envelope, why user actions come
in pairs with a shared `attempt_id`, and why the report has a **Log health**
section that counts intents without matching outcomes. If 71 fixes were asked
for and none reported back, the report says so on the first line.

## Where it lives

| | |
| --- | --- |
| File | `files/diagnostics.log` — the app's own private storage |
| Format | one JSON object per line (JSONL) |
| Writer | `Diag.event(...)`, called **only** from `Events.kt` |
| Schema | `tools/logging_schema.json` |
| CI gate | `tools/validate_logging.py`, run by `.github/workflows/build.yml` |
| UI | Settings → Diagnostics |

There is no transport. The app has no internet permission, so a log can only
leave the phone when the user exports, shares or mails it.

## The envelope

Every event carries these keys, added automatically by `Diag`:

`event_name` · `schema_version` · `timestamp` (ISO 8601 UTC) · `session_id` ·
`platform` · `app_id` · `app_version` · `os` · `device_model` · `network_type` ·
`screen_or_route` · `category` · `log_level` · `release_channel`

Two fields the standard asks for are carried by something already here, rather
than duplicated:

- **`severity`** is `log_level`.
- **`environment`** is `release_channel` (production / beta).
- **`request_id`** does not exist, because there are no requests. `attempt_id`
  links one user action to its outcome instead.

### Severity

The six levels the standard lists are all used:

| Level | Used for |
| --- | --- |
| `trace` | available; nothing is fine-grained enough to need it yet |
| `debug` | internals worth knowing when something misbehaves — the bubble stepping aside, say |
| `info` | ordinary events: screens, launches, searches, settings |
| `warn` | a potential problem: rage taps, freezes, an error put on screen |
| `error` | an operation failed: a file index rebuild, a report that would not write |
| `fatal` | a crash |

`attempt_id` is added where an action has an outcome. Event-specific properties
sit alongside these as flat keys.

`user_id` is **omitted entirely**: the app has no accounts and no sign-in, so
there is no identifier to hash — and none is invented to fill the gap.

## Events

**Session** — `session_start`, `drop_off`, `screen_view`

**Permissions** — `permission_state_resolved`, `access_fix_requested`,
`access_fix_result`, `permission_loop`

**The list and launching** — `app_list_loaded`, `media_query_result`,
`app_launch_requested`, `app_launch_result`, `search_performed`,
`search_no_results`

**Preferences and screens** — `settings_changed`, `bubble_toggled`,
`diagnostics_action`, `guide_step`, `guide_finished`, `tea_mode`

**Health** — `performance_marker`, `error_state_shown`, `crash`, `previous_run_crashed`, `anr`

**Adoption** — `wheel_opened`

**Background** — `file_index_rebuild`

**Debug** — `bubble_hidden`

**Friction signals** — `rage_tap`, `dead_click`, `back_button_spam`,
`scroll_depth`, `time_to_first_value`

The per-event properties, levels and enums are all in the schema file; that is
the single source of truth, and CI enforces it.

## The twelve categories, mapped

The shared standard lists twelve kinds of log. This is what each one means for a
local, offline Android launcher — including the ones that cannot exist here.

| # | Category | Here |
| --- | --- | --- |
| 1 | Application | **yes** — `session_start`, screens, launches, settings |
| 2 | Error | **yes** — `crash` (fatal, with the stack trace), `previous_run_crashed`, `anr`, `error_state_shown` |
| 3 | Security | **yes** — `permission_state_resolved`, `access_fix_requested`/`_result`, `permission_loop`. No logins and no requests exist to log |
| 4 | Performance | **yes** — `performance_marker`, `app_list_loaded`, `media_query_result`, plus memory in the report |
| 5 | User activity | **yes** — searches, scroll depth, rage taps, dead clicks, guide steps, tea mode |
| 6 | Server & infrastructure | **no** — there is no server, no deployment and no service to monitor |
| 7 | API & network | **no** — the app has no internet permission, so it can never make a call |
| 8 | Database | **no** — nothing is stored beyond SharedPreferences and the log file |
| 9 | Audit | **yes** — `settings_changed` and `diagnostics_action`: who changed what, when, and which setting |
| 10 | Analytics & business | **yes, locally** — the report's **Usage (local analytics)** section counts sessions, days covered, most-used screens, searches, the launch funnel and feature adoption from the events already recorded. No analytics SDK, no telemetry and no upload: the app has no internet permission |
| 11 | Background jobs | **yes** — `file_index_rebuild`, the one real background job: a walk of storage |
| 12 | Debug & trace | **yes** — `bubble_hidden` at `debug` level; distributed tracing does not apply to one process on one device |

Every event carries a `category`, the report prints a count per category, and
the schema check in CI fails if an event's category is missing or does not match
the schema. A category sitting at zero in the report is honest: it says the
category applies and simply has not happened yet.

### Crash reporting without a service

There is no Sentry, no Crashlytics and no Firebase, because all of them upload
and this app cannot reach the network. What it does instead:

- The uncaught-exception handler writes the **full stack trace** (redacted, capped
  at 4000 characters) together with the exception type, the screen, the last
action, the thread, whether it was the main thread, and how long the process had
  been alive.
- A `fatal` record is written **synchronously**, on the crashing thread. Every
  other event goes to a background writer, but the writer's queue would die with
  the process — so the one record that matters most is the one exception to that
  rule.
- On the next start, if the last thing in the log is a crash, that is recorded as
  `previous_run_crashed`. It is the only way a crash loop can become visible from
  inside the app.
- The report has a **Crash reports** section with the last three crashes and the
  first frames of each trace. The whole trace is in the `.json` and `.csv`
  exports.

### Not applicable here

`api_request`, `page_view`, `web_vitals`, `js_error`, `form_start`,
`form_abandon`, `form_submit`, `conversion`, `feedback_submitted`,
`session_replay_marker`, `login`, and the server/database/tracing rows above are
declared in the schema as **not applicable**, each with the reason. The first is
the interesting one: this app cannot make a network call, so it can never log
one.

The analytics row is the one that moved. It used to read *no*; it now reads
*yes, locally*, because the honest answer was never that the app has no idea how
it is used — it was that the app has no way to send that anywhere. A local view
over the log answers the question without breaking the promise.

## Privacy

The app is India-facing, so this is a DPDP Act 2023 obligation as well as good
practice. The rules are the same on app and web.

**Never logged:** email addresses, phone numbers, names, exact location, raw
file paths, media file names, raw search queries, auth tokens, or any identifier
of a person. Search records the **length** of the query and the result count,
never the text.

**Redaction**, applied by `Diag.redact()` to every string value before it is
written:

- anything shaped like an email → `[email]`
- a run of seven or more digits → `[number]`
- an absolute filesystem path → `[path]`
- values are capped at 240 characters

**Logged on purpose:** the package name of an app the user opened
(`com.example.app`). It is a public product identifier rather than personal
data, and a launch problem cannot be debugged without it. The user-visible app
label is never recorded, because a label can contain a person's own words.

**Retention:** 14 days, pruned automatically on start. **Size cap:** 512 KB, with
the file rotated automatically. **Sampling:** high-volume info events
(`screen_view`, `scroll_depth`, `performance_marker`) keep one record in five;
`warn` and `error` records and anything carrying an `attempt_id` are always
kept.

**Consent / opt-out:** Settings → **Record diagnostics**, on by default. While
it is off, nothing at all is written — not even the event that would record the
switch being turned off.

**Access control:** the log is the app's own private file. No other app on the
phone can read it, there is no server that holds a copy, and it leaves only
through the export, share or mail buttons the user presses themselves. There is
no dashboard to restrict, because there is no dashboard.

## Adding an event

1. Add it to `tools/logging_schema.json` with its level, `when`, `required` and
   `optional` properties. Bump `schema_version` if the change is breaking.
2. Add a typed constructor to `Events.kt` using the `log(...)` helper.
3. Call it. **Do not** call `Diag.event` anywhere else — the CI check fails if
   you do.
4. Run `python3 tools/validate_logging.py`. It checks event names, property
   names, required properties, log levels, enum drift, and that every event in
   the schema is actually fired somewhere. A schema event that never fires is an
   error, because a log that never fires is worse than none.
5. Test it on a real device. The gate proves the event is well-formed, not that
   it is reached.

## Reading a report

Settings → Diagnostics shows a live preview of exactly what would be exported,
and offers four shapes:

| Format | Good for |
| --- | --- |
| `.txt` | reading in a mail or notes app |
| `.md` | pasting into a GitHub issue |
| `.json` | the exact structured records, one object per event |
| `.csv` | one row per event with every property as a column — Excel or Google Sheets |

The report also carries **Signals** (rage taps, dead clicks, permission loops,
back-button spam, errors, freezes, crashes, deepest scroll, time to first
action, launch outcomes) and **Log health** (intents vs outcomes, and how many
attempts are unmatched). Those two sections exist to make friction visible
before a user has to describe it.
