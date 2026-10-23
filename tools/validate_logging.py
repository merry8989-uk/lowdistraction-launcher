#!/usr/bin/env python3
"""Validate the app's logging against tools/logging_schema.json.

This is the review gate the logging standard asks for: an event that is not in
the schema, a missing required property, an unknown property, a wrong log level
or a drifted enum fails the build rather than shipping quietly.

It works on one convention, which is why it can be exact:

  * Every event is declared exactly once in Events.kt, as
        log(c, "event_name", "level", "key" to value, ...)
  * Nothing else in the app calls Diag.event / Diag.write directly.

Run from the repository root:  python3 tools/validate_logging.py
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_PATH = os.path.join(ROOT, "tools", "logging_schema.json")
EVENTS_KT = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "lowdistraction", "launcher", "Events.kt"
)
JAVA_ROOT = os.path.join(ROOT, "app", "src", "main", "java")

# The Kotlin enum that carries each schema enum's values.
KOTLIN_ENUMS = {
    "StartType": "start_type",
    "PermState": "permission_state",
    "Outcome": "outcome",
    "LaunchSource": "launch_source",
    "SearchSource": "search_source",
    "ExportFormat": "export_format",
    "ExportChannel": "export_channel",
    "DiagAction": "diagnostics_action",
    "TeaAction": "tea_action",
    "Category": "category",
}

# Kotlin constant -> schema value, for Category.X.value
CATEGORY_CONST = {
    "APPLICATION": "application",
    "ACTIVITY": "activity",
    "ERROR": "error",
    "SECURITY": "security",
    "PERFORMANCE": "performance",
    "AUDIT": "audit",
    "BACKGROUND": "background",
    "DEBUG": "debug",
}

# name, SEVERITY, category — a call without a category will not match at all, so
# the "declared but never logged" check catches it.
CALL_START = re.compile(
    r"\blog\(\s*c\s*,\s*\"([a-z0-9_]+)\"\s*,\s*\"([A-Za-z]+)\"\s*,\s*"
    r"([A-Za-z_][A-Za-z0-9_.]*)"
)


def category_of(expr):
    """The schema value behind a Category.X.value reference or a literal."""
    m = re.fullmatch(r"Category\.([A-Z_]+)\.value", expr)
    if m:
        return CATEGORY_CONST.get(m.group(1))
    if len(expr) > 1 and expr.startswith('"') and expr.endswith('"'):
        return expr[1:-1]
    return None
KEY_ANY = re.compile(r"\"([a-z0-9_]+)\"\s*to\s")
KEY_LITERAL = re.compile(r"\"([a-z0-9_]+)\"\s*to\s*\"([^\"]*)\"")


def find_call_end(text, open_index):
    """Index of the ')' that closes the call whose '(' is at open_index."""
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return len(text)


def validate():
    schema = json.load(open(SCHEMA_PATH, encoding="utf-8"))
    events = schema["events"]
    enums = schema["enums"]
    problems = []
    seen = {}

    if not os.path.exists(EVENTS_KT):
        print("FATAL: Events.kt not found — every event must be declared there.")
        return 1
    text = open(EVENTS_KT, encoding="utf-8").read()

    for m in CALL_START.finditer(text):
        name, level = m.group(1), m.group(2)
        category = category_of(m.group(3))
        end = find_call_end(text, m.start() + text[m.start():].index("("))
        body = text[m.end():end]

        spec = events.get(name)
        if spec is None:
            problems.append(f"unknown event_name '{name}' (add it to logging_schema.json)")
            continue
        seen[name] = seen.get(name, 0) + 1

        if level != spec["level"]:
            problems.append(f"{name}: level '{level}' but the schema says '{spec['level']}'")
        if level not in enums["log_level"]:
            problems.append(f"{name}: '{level}' is not a valid log_level")

        want_category = spec.get("category")
        if category is None:
            problems.append(
                f"{name}: category must be Category.X.value or a literal, "
                f"got '{m.group(3)}'"
            )
        elif category not in enums["category"]:
            problems.append(f"{name}: category '{category}' is not a schema category")
        elif want_category and category != want_category:
            problems.append(
                f"{name}: category '{category}' but the schema says '{want_category}'"
            )

        keys = set(KEY_ANY.findall(body))
        literals = dict(KEY_LITERAL.findall(body))

        allowed = set(spec.get("required", [])) | set(spec.get("optional", []))
        for key in sorted(keys - allowed):
            problems.append(f"{name}: property '{key}' is not in the schema")
        for key in spec.get("required", []):
            if key not in keys:
                problems.append(f"{name}: missing required property '{key}'")

        for key, value in literals.items():
            for enum_name, values in enums.items():
                # Only complain when the value looks like it was meant to be one
                # of a known enum's members.
                if value in values:
                    break
            else:
                if key in ("state", "state_before", "state_after", "outcome",
                           "start_type", "action", "format", "channel"):
                    problems.append(
                        f"{name}.{key}: '{value}' is not a value of any schema enum"
                    )

    for name in events:
        if name not in seen:
            problems.append(f"{name}: declared in the schema but never logged")

    # Nothing outside Events.kt may call the logger directly.
    outside = {}
    for dirpath, _dirs, files in os.walk(JAVA_ROOT):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(dirpath, f)
            if os.path.abspath(path) == os.path.abspath(EVENTS_KT):
                continue
            body = open(path, encoding="utf-8").read()
            outside[os.path.relpath(path, ROOT)] = body
            if "Diag.event(" in body or "Diag.write(" in body:
                problems.append(
                    f"{os.path.relpath(path, ROOT)}: calls Diag.event/write directly — "
                    "route it through Events.kt"
                )
    outside_all = "\n".join(outside.values())

    # A declared event the app never calls is worse than none: the schema would
    # promise data that never arrives. Check the call sites, not just the
    # declaration — that is how four dead events slipped through before.
    # An event another event calls internally (permission_loop) counts as wired.
    declared_fns = set(
        re.findall(r"^\s{4}fun ([A-Za-z][A-Za-z0-9]*)\([^)]*\)\s*[:=]", text, re.M)
    )
    declared_fns.discard("log")
    # Strip the declarations first: otherwise every function name matches its own
    # "fun name(" line and the check below can never fail.
    body_only = re.sub(r"\bfun\s+[A-Za-z][A-Za-z0-9]*\s*\(", "FUN(", text)
    internal_calls = set(re.findall(r"(?<![\w.])([A-Za-z][A-Za-z0-9]*)\s*\(", body_only))
    for fn in sorted(declared_fns):
        if f"Events.{fn}(" in outside_all or fn in internal_calls:
            continue
        problems.append(f"{fn}(): declared in Events.kt but never called from the app")

    # LogGroups.kt decides where each property sits in the nested record. It must
    # agree with the schema, or a property would be filed in the wrong group.
    groups_kt = os.path.join(
        JAVA_ROOT, "com", "lowdistraction", "launcher", "LogGroups.kt"
    )
    if not os.path.exists(groups_kt):
        problems.append("LogGroups.kt is missing — Diag needs it to nest an event")
    else:
        kt = open(groups_kt, encoding="utf-8").read()
        declared = dict(re.findall(r'"([a-z0-9_]+)" to ([A-Z]+),', kt))
        const = {
            "CONTEXT": "context",
            "CORRELATION": "correlation",
            "ERROR": "error",
            "METRICS": "metrics",
        }
        wanted = {}
        for spec in schema["events"].values():
            for group, keys in spec.get("groups", {}).items():
                for key in keys:
                    wanted[key] = group
        for key in sorted(wanted):
            got = const.get(declared.get(key, ""))
            if got is None:
                problems.append(
                    f"LogGroups.kt does not place '{key}' (the schema puts it in {wanted[key]})"
                )
            elif got != wanted[key]:
                problems.append(
                    f"LogGroups.kt puts '{key}' in {got}; the schema says {wanted[key]}"
                )
        for key in sorted(set(declared) - set(wanted)):
            problems.append(f"LogGroups.kt places '{key}', which no event uses")

    # The Kotlin enums must not drift from the schema enums.
    for kt_name, schema_name in KOTLIN_ENUMS.items():
        block = re.search(
            rf"enum class {kt_name}\(val value: String\)\s*\{{(.*?)\}}", text, re.S
        )
        if not block:
            problems.append(f"enum class {kt_name} not found in Events.kt")
            continue
        declared = re.findall(r"\"([a-z0-9_]+)\"", block.group(1))
        allowed = enums.get(schema_name, [])
        unknown = [d for d in declared if d not in allowed]
        if unknown:
            problems.append(
                f"enum {kt_name}: {unknown} is not in schema enum '{schema_name}'"
            )

    if problems:
        print("Logging schema validation FAILED:\n")
        for p in problems:
            print("  -", p)
        print(f"\n{len(problems)} problem(s).")
        return 1

    print(
        f"Logging schema OK — {len(seen)} events, "
        f"schema_version {schema['schema_version']}."
    )
    return 0


if __name__ == "__main__":
    sys.exit(validate())
