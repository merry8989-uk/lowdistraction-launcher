# Logging & diagnostics

Low Distraction records a **structured event log** so that a problem can be
diagnosed from a report the user sends, instead of from guesswork. The schema is
shared with the ProGallery logging standard, so events from both can be joined
and compared.

Schema version: **1.0.0** · canonical definition: [`tools/logging_schema.json`](../tools/logging_schema.json)

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
`platform` · `app_version` · `os` · `device_model` · `network_type` ·
`screen_or_route` · `log_level` · `release_channel`

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

**Health** — `performance_marker`, `error_state_shown`, `crash`, `anr`

**Friction signals** — `rage_tap`, `dead_click`, `back_button_spam`,
`scroll_depth`, `time_to_first_value`

The per-event properties, levels and enums are all in the schema file; that is
the single source of truth, and CI enforces it.

### Not applicable here

`api_request`, `page_view`, `web_vitals`, `js_error`, `form_start`,
`form_abandon`, `form_submit`, `conversion`, `feedback_submitted` and
`session_replay_marker` are declared in the schema as **not applicable**. The
first is the interesting one: this app cannot make a network call, so it can
never log one. The web-only events and session replay simply do not exist here.

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
switch being turned off. Access to the log is limited to whoever holds the
phone: it is the app's own private file, and it leaves only through the export,
share or mail buttons.

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
