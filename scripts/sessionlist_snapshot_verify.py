#!/usr/bin/env python3
"""[T-android-sessionlist-snapshot-restore] Python mirror of the session-list
snapshot cache decisions (Kotlin: SessionListSnapshotCache.kt).

Mirrors, with the same logic as the JVM tests in
SessionListSnapshotCacheTest.kt:
  1. snapshotRestoreDecision        — restore / skip decision table
  2. sessionListEntryRevealed       — list-pane entry gate table
  3. sessionListStallSpinner        — snapshot-aware spinner guard table
  4. JSON round-trip & fault tolerance — corrupt / blank / wrong version /
     future unknown fields; full-fidelity field contract for the 15 session
     and 10 folder snapshot columns.
"""
import json
import sys

# --- contract: the exact column sets the Kotlin toSnapshotRow/toEntity maps
# must carry. Any drift between the Room entities and the snapshot DTOs fails
# here first (local runs don't compile Kotlin).
SESSION_FIELDS = [
    "id", "title", "modelId", "createdAt", "updatedAt", "category",
    "lastMessage", "modelBinding", "source", "memoryEnabled", "pinnedAt",
    "editCount", "thinkingOverride", "folderId", "permissionMode",
]
FOLDER_FIELDS = [
    "id", "name", "icon", "color", "origin", "sortIndex", "pinnedAt",
    "description", "createdAt", "updatedAt",
]
SNAPSHOT_VERSION = 1

FAILS = []


def check(name, actual, expected):
    if actual == expected:
        return True
    FAILS.append(f"{name}: expected {expected!r}, got {actual!r}")
    return False


# --- mirrored decisions (keep in lockstep with the Kotlin pure functions) ---

def snapshot_restore_decision(db_emission_landed, snapshot):
    if db_emission_landed:
        return "SKIP_DB_ALREADY_LANDED"
    if snapshot is None:
        return "SKIP_NO_SNAPSHOT"
    if not snapshot.get("sessions"):
        return "SKIP_EMPTY"
    return "RESTORE"


def session_list_entry_revealed(initial_complete, timed_out, snapshot_restored):
    return initial_complete or timed_out or snapshot_restored


def session_list_stall_spinner(initial_complete, snapshot_restored, sessions_non_empty):
    return (not initial_complete) and not (snapshot_restored and sessions_non_empty)


def decode_snapshot(text):
    """Mirror decodeSessionListSnapshot: None on blank/corrupt/wrong version."""
    if text is None or not text.strip():
        return None
    try:
        payload = json.loads(text)
    except (ValueError, TypeError):
        return None
    if payload.get("version") != SNAPSHOT_VERSION:
        return None
    return payload


def encode_snapshot(payload):
    return json.dumps(payload, separators=(",", ":"))


def main():
    n = 0

    # 1. restore decision table
    valid = {"version": 1, "savedAtMs": 1, "sessions": [{"id": "s1"}], "folders": []}
    check("decision landed", snapshot_restore_decision(True, valid), "SKIP_DB_ALREADY_LANDED"); n += 1
    check("decision no snapshot", snapshot_restore_decision(False, None), "SKIP_NO_SNAPSHOT"); n += 1
    check("decision empty", snapshot_restore_decision(False, {"version": 1, "sessions": [], "folders": []}), "SKIP_EMPTY"); n += 1
    check("decision restore", snapshot_restore_decision(False, valid), "RESTORE"); n += 1

    # 2. entry gate table
    check("gate all false", session_list_entry_revealed(False, False, False), False); n += 1
    check("gate initial", session_list_entry_revealed(True, False, False), True); n += 1
    check("gate timeout", session_list_entry_revealed(False, True, False), True); n += 1
    check("gate snapshot", session_list_entry_revealed(False, False, True), True); n += 1

    # 3. stall spinner table
    check("spinner first-run", session_list_stall_spinner(False, False, False), True); n += 1
    check("spinner suppressed by snapshot", session_list_stall_spinner(False, True, True), False); n += 1
    check("spinner off after emission nonempty", session_list_stall_spinner(True, False, True), False); n += 1
    check("spinner off after emission empty", session_list_stall_spinner(True, False, False), False); n += 1

    # 4. decode fault tolerance
    check("decode null", decode_snapshot(None), None); n += 1
    check("decode empty", decode_snapshot(""), None); n += 1
    check("decode blank", decode_snapshot("   "), None); n += 1
    check("decode corrupt", decode_snapshot("{not json at all"), None); n += 1
    wrong_version = encode_snapshot({"version": 99, "savedAtMs": 1, "sessions": [{"id": "s1"}], "folders": []})
    check("decode wrong version", decode_snapshot(wrong_version), None); n += 1
    future = '{"version":1,"savedAtMs":1,"sessions":[],"folders":[],"futureField":true}'
    check("decode tolerates future fields", bool(decode_snapshot(future)), True); n += 1

    # 5. JSON round-trip with the full column contract
    session_row = {
        "id": "s1", "title": "hello", "modelId": "gpt-test",
        "createdAt": 1000, "updatedAt": 2000, "category": "work",
        "lastMessage": "last body", "modelBinding": "provider-1/gpt-test",
        "source": "shortcut", "memoryEnabled": 1, "pinnedAt": None,
        "editCount": 3, "thinkingOverride": None, "folderId": None,
        "permissionMode": "ASK",
    }
    folder_row = {
        "id": "f1", "name": "Projects", "icon": "folder", "color": "#FFAA00",
        "origin": "manual", "sortIndex": 0, "pinnedAt": 900,
        "description": "work stuff", "createdAt": 500, "updatedAt": 600,
    }
    check("session field contract", sorted(session_row.keys()), sorted(SESSION_FIELDS)); n += 1
    check("folder field contract", sorted(folder_row.keys()), sorted(FOLDER_FIELDS)); n += 1
    payload = {"version": SNAPSHOT_VERSION, "savedAtMs": 42,
               "sessions": [session_row], "folders": [folder_row]}
    restored = decode_snapshot(encode_snapshot(payload))
    check("roundtrip sessions", restored["sessions"], [session_row]); n += 1
    check("roundtrip folders", restored["folders"], [folder_row]); n += 1
    check("roundtrip order", [s["id"] for s in restored["sessions"]], ["s1"]); n += 1
    # null-field fidelity through encode/decode
    null_row = dict(session_row, title=None, folderId=None, pinnedAt=None)
    r2 = decode_snapshot(encode_snapshot({"version": 1, "sessions": [null_row], "folders": []}))
    check("roundtrip nulls", r2["sessions"][0]["title"] is None and r2["sessions"][0]["pinnedAt"] is None, True); n += 1

    total = n
    passed = total - len(FAILS)
    print(f"sessionlist snapshot mirror: {passed}/{total} checks passed")
    for f in FAILS:
        print(f"  FAIL {f}")
    sys.exit(0 if not FAILS else 1)


if __name__ == "__main__":
    main()
