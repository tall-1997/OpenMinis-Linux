#!/usr/bin/env python3
"""Python mirror of InstructionStateTest.kt (P1 instruction snapshots).

Pins the pure-function contract in Python so the sandbox can verify the
serialization / diff / render behaviour without gradle. Mirrors
src/android/app/src/main/java/com/openminis/app/data/instruction/InstructionState.kt
(the Kotlin file is the source of truth; keep the two same-shaped).

Usage: python3 scripts/ci/instruction_state_verify.py
Exit 0 = all green.
"""

KNOWN_KEYS = ["goal", "app-global", "session-global"]
KNOWN = set(KNOWN_KEYS)
RENDER_VALUE_LIMIT = 240


def sanitize_values(values):
    return {k: v for k, v in values.items() if k in KNOWN and v != ""}


def observe(stored_values, current):
    sanitized = sanitize_values(current)
    if stored_values is None:
        return {
            "initial": True,
            "previous": {},
            "current": sanitized,
            "delta": dict(sanitized),
        }
    previous = sanitize_values(stored_values)
    changes = {}
    for key in KNOWN_KEYS:
        had = key in previous
        has = key in sanitized
        if has and (not had or previous[key] != sanitized[key]):
            changes[key] = sanitized[key]
        elif had and not has:
            changes[key] = None
    return {
        "initial": False,
        "previous": previous,
        "current": sanitized,
        "delta": changes,
    }


def apply_delta(values, delta):
    nxt = dict(sanitize_values(values))
    for key, value in delta.items():
        if value is None:
            nxt.pop(key, None)
        else:
            nxt[key] = value
    return sanitize_values(nxt)


def clip(value):
    if len(value) <= RENDER_VALUE_LIMIT:
        return value
    return value[:RENDER_VALUE_LIMIT] + "[truncated]"


def render_update(previous, current, delta):
    # Insertion order (mirrors Kotlin's linkedMapOf: observe() fills changes in
    # KNOWN_KEYS order, so goal comes before app-global before session-global).
    lines = []
    for key, new_value in delta.items():
        if new_value is None:
            lines.append("- %s" % key)
        else:
            if key in previous:
                lines.append("- %s: %s" % (key, clip(previous[key])))
            lines.append("+ %s: %s" % (key, clip(new_value)))
    return "\n".join(lines)


def render_initial(current):
    return "\n".join("+ %s: %s" % (k, clip(v)) for k, v in sorted(current.items()))


def escape_json(text):
    out = []
    for c in text:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c == "\n":
            out.append("\\n")
        elif c == "\r":
            out.append("\\r")
        elif c == "\t":
            out.append("\\t")
        elif ord(c) < 0x20:
            out.append("\\u%04x" % ord(c))
        else:
            out.append(c)
    return "".join(out)


def values_json(values):
    # Pure serialization, no semantic filtering: nested JSON values (goal value
    # is itself a JSON object) must round-trip through parse_values intact.
    # Filtering is the callers' concern (observe/apply_delta sanitize at entry).
    entries = ",".join(
        '"%s":"%s"' % (escape_json(k), escape_json(v))
        for k, v in sorted(values.items())
    )
    return "{%s}" % entries


def delta_json(delta):
    entries = ",".join(
        ('"%s":null' % escape_json(k)) if v is None
        else ('"%s":"%s"' % (escape_json(k), escape_json(v)))
        for k, v in sorted(delta.items())
    )
    return "{%s}" % entries


import json as _json


def parse_nullable_values(text):
    """
    Shared parser admitting JSON null literal values (mirrors Kotlin).
    Pure decoding: no KNOWN-key filter here, so nested instruction values
    round-trip intact. Semantic filtering is sanitize_values' job.
    """
    try:
        parsed = _json.loads(text)
    except ValueError:
        return None
    if not isinstance(parsed, dict):
        return None
    result = {}
    for k, v in parsed.items():
        if not isinstance(k, str) or not (v is None or isinstance(v, str)):
            return None
        result[k] = v
    return result


def parse_delta(text):
    parsed = parse_nullable_values(text)
    if parsed is None:
        return {}
    return parsed


def parse_values(text):
    """
    Pure JSON decoding (mirrors Kotlin): unknown keys preserved so nested
    instruction values round-trip intact.
    """
    try:
        parsed = _json.loads(text)
    except ValueError:
        return {}
    if not isinstance(parsed, dict):
        return {}
    result = {}
    for k, v in parsed.items():
        if isinstance(k, str) and isinstance(v, str):
            result[k] = v
    return result


CHECKS = []


def check(name, fn):
    CHECKS.append((name, fn))


def test_observe_initial():
    obs = observe(None, {"goal": "A", "app-global": "B"})
    assert obs["initial"] is True
    assert obs["previous"] == {}
    assert obs["delta"] == {"goal": "A", "app-global": "B"}


def test_observe_no_change_is_empty():
    obs = observe({"goal": "A"}, {"goal": "A"})
    assert obs["initial"] is False
    assert obs["delta"] == {}


def test_observe_changed_added_removed():
    obs = observe({"goal": "A", "app-global": "B"}, {"goal": "A2", "session-global": "C"})
    assert obs["delta"] == {"goal": "A2", "app-global": None, "session-global": "C"}


def test_observe_sanitizes_unknown_and_empty():
    obs = observe(None, {"goal": "A", "junk": "x", "app-global": "", "session-global": "S"})
    assert obs["current"] == {"goal": "A", "session-global": "S"}
    assert "junk" not in obs["delta"]


def test_apply_delta_fold_and_roundtrip():
    stored = {"goal": "A", "app-global": "B"}
    current = {"goal": "A2", "session-global": "C"}
    obs = observe(stored, current)
    folded = apply_delta(stored, obs["delta"])
    assert folded == current
    # removal only
    assert apply_delta({"goal": "A"}, {"goal": None}) == {}


def test_render_update_lines():
    prev = {"goal": "A", "app-global": "B"}
    curr = {"goal": "A2", "session-global": "C"}
    delta = observe(prev, curr)["delta"]
    assert render_update(prev, curr, delta) == (
        "- goal: A\n"
        "+ goal: A2\n"
        "- app-global\n"
        "+ session-global: C"
    )
    assert render_update({"goal": "A"}, {}, {"goal": None}) == "- goal"


def test_render_clips_at_240():
    long_v = "x" * 500
    assert clip(long_v) == "x" * 240 + "[truncated]"
    assert clip("x" * 240) == "x" * 240
    rendered = render_update({}, {"goal": long_v}, {"goal": long_v})
    assert rendered.endswith("[truncated]")


def test_render_initial_sorted():
    assert render_initial({"goal": "G", "app-global": "A"}) == "+ app-global: A\n+ goal: G"
    assert render_initial({}) == ""


def test_values_json_roundtrip():
    values = {"goal": "说\"中文\"\\", "app-global": "line1\nline2\ttab"}
    encoded = values_json(values)
    assert parse_values(encoded) == values
    assert values_json({}) == "{}"


def test_parse_values_bad_and_unknown():
    assert parse_values("not json") == {}
    # Pure decoding preserves unknown keys; semantic filtering is sanitize_values.
    assert parse_values('{"goal":"A","junk":"x"}') == {"goal": "A", "junk": "x"}
    assert sanitize_values(parse_values('{"goal":"A","junk":"x"}')) == {"goal": "A"}


def test_delta_json_roundtrip_with_null():
    delta = {"goal": "A2", "app-global": None}
    encoded = delta_json(delta)
    assert parse_delta(encoded) == delta
    assert delta_json({}) == "{}"
    assert parse_delta("bad") == {}


def test_escape_control_chars():
    assert escape_json("\x01") == "\\u0001"
    assert escape_json('a"b') == 'a\\"b'


def test_values_json_nested_roundtrip():
    # Nested JSON values (e.g. goal value itself is a JSON object) must survive
    # values_json→parse_values round-trip without key filtering.
    goal_value = values_json({"objective": "ship it", "status": "ACTIVE", "tokenBudget": "8000"})
    encoded = values_json({"goal": goal_value})
    parsed = parse_values(encoded)
    parsed_inner = parse_values(parsed["goal"])
    assert parsed_inner["objective"] == "ship it"
    assert parsed_inner["status"] == "ACTIVE"


def test_sanitize_drops_empty_values():
    assert sanitize_values({"goal": "", "app-global": "B"}) == {"app-global": "B"}


def main():
    check("observe initial", test_observe_initial)
    check("observe no change empty delta", test_observe_no_change_is_empty)
    check("observe changed/added/removed", test_observe_changed_added_removed)
    check("observe sanitizes unknown+empty", test_observe_sanitizes_unknown_and_empty)
    check("applyDelta fold + roundtrip", test_apply_delta_fold_and_roundtrip)
    check("renderUpdate lines", test_render_update_lines)
    check("clip at 240", test_render_clips_at_240)
    check("renderInitial sorted", test_render_initial_sorted)
    check("valuesJson roundtrip", test_values_json_roundtrip)
    check("parseValues bad/unknown", test_parse_values_bad_and_unknown)
    check("deltaJson roundtrip + null", test_delta_json_roundtrip_with_null)
    check("escape control chars", test_escape_control_chars)
    check("sanitize drops empty", test_sanitize_drops_empty_values)
    check("valuesJson nested roundtrip", test_values_json_nested_roundtrip)
    failed = 0
    for name, fn in CHECKS:
        try:
            fn()
            print("  ok  %s" % name)
        except AssertionError as exc:
            failed += 1
            print("FAIL  %s: %s" % (name, exc))
    print("%d/%d green" % (len(CHECKS) - failed, len(CHECKS)))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
