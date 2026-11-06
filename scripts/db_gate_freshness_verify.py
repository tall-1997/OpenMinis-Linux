#!/usr/bin/env python3
"""[T-db-gate-freshness] Python mirror of DatabaseGateFreshness (Kotlin
src/android/app/src/main/java/com/openminis/app/data/db/DatabaseGateFreshness.kt).

Mirrors the freshness decision table, stamp IO (tmp+rename+fsync) and the
gate integration contract: a fresh verdict skips the O(size) quick_check, a
passing scan writes the stamp, a failing scan / missing database invalidates
it. Run: python3 -I scripts/db_gate_freshness_verify.py
"""
import json
import os
import tempfile

FRESH_WINDOW_MS = 24 * 60 * 60 * 1000
STAMP = "db_gate_freshness.json"


def is_fresh(stamp, db_path, db_size, now_ms):
    if stamp is None:
        return False
    if not (0 <= now_ms - stamp["checkedAtMs"] < FRESH_WINDOW_MS):
        return False
    if stamp["dbPath"] != db_path:
        return False
    if stamp["dbSizeBytes"] != db_size:
        return False
    return True


def read_stamp(files_dir):
    p = os.path.join(files_dir, STAMP)
    if not os.path.isfile(p):
        return None
    try:
        with open(p, encoding="utf-8") as f:
            o = json.load(f)
        return {
            "checkedAtMs": int(o["checkedAtMs"]),
            "dbPath": str(o["dbPath"]),
            "dbSizeBytes": int(o["dbSizeBytes"]),
        }
    except Exception:
        return None


def record_healthy(db_path, db_size, files_dir, now_ms):
    p = os.path.join(files_dir, STAMP)
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"checkedAtMs": now_ms, "dbPath": db_path, "dbSizeBytes": db_size}, f)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, p)  # tmp+rename, mirrors the Kotlin write


def invalidate(files_dir):
    try:
        os.remove(os.path.join(files_dir, STAMP))
    except OSError:
        pass


def gate(db_exists, db_path, db_size, checker_result, files_dir, now_ms):
    """Mirror of ensureHealthyOrQuarantined's flow with freshness inserted."""
    if not db_exists:
        invalidate(files_dir)
        return "FreshInstall"
    if is_fresh(read_stamp(files_dir), db_path, db_size, now_ms):
        return "Healthy"  # cached — checker not invoked
    if checker_result is None:
        record_healthy(db_path, db_size, files_dir, now_ms)
        return "Healthy"
    invalidate(files_dir)
    return "Quarantined"


checks = []


def check(name, cond):
    checks.append((name, bool(cond)))


NOW = 1_700_000_000_000

# -- decision table --------------------------------------------------------
check("no stamp scans", not is_fresh(None, "/x/minis.db", 1024, NOW))
stamp = {"checkedAtMs": NOW, "dbPath": "/x/minis.db", "dbSizeBytes": 1024}
check("inside window fresh", is_fresh(stamp, "/x/minis.db", 1024, NOW + 60_000))
check("window edge inclusive", is_fresh(stamp, "/x/minis.db", 1024, NOW + FRESH_WINDOW_MS - 1))
check("expiry forces rescan", not is_fresh(stamp, "/x/minis.db", 1024, NOW + FRESH_WINDOW_MS))
check("clock rollback stale", not is_fresh(stamp, "/x/minis.db", 1024, NOW - 1))
check("path mismatch rescan", not is_fresh(stamp, "/x/other.db", 1024, NOW))
check("size mismatch rescan", not is_fresh(stamp, "/x/minis.db", 1025, NOW))

# -- stamp IO ----------------------------------------------------------------
with tempfile.TemporaryDirectory() as d:
    record_healthy("/x/minis.db", 1024, d, NOW)
    s = read_stamp(d)
    check("round-trip", s == {"checkedAtMs": NOW, "dbPath": "/x/minis.db", "dbSizeBytes": 1024})
    check("isFresh honors round-trip", is_fresh(s, "/x/minis.db", 1024, NOW + 1))
with tempfile.TemporaryDirectory() as d:
    with open(os.path.join(d, STAMP), "w", encoding="utf-8") as f:
        f.write("not json at all")
    check("corrupt stamp null", read_stamp(d) is None)
with tempfile.TemporaryDirectory() as d:
    check("missing stamp null", read_stamp(d) is None)
with tempfile.TemporaryDirectory() as d:
    record_healthy("/x/minis.db", 1024, d, NOW)
    invalidate(d)
    check("invalidate removes", read_stamp(d) is None)

# -- gate integration contract -------------------------------------------------
with tempfile.TemporaryDirectory() as d:
    record_healthy("/x/minis.db", 1024, d, NOW)
    r = gate(True, "/x/minis.db", 1024, None, d, NOW)
    check("cached verdict skips checker", r == "Healthy")
    check("stamp preserved on cache hit", read_stamp(d) is not None)
with tempfile.TemporaryDirectory() as d:
    r = gate(True, "/x/minis.db", 1024, None, d, NOW)
    check("passing scan writes stamp", r == "Healthy" and read_stamp(d) is not None)
with tempfile.TemporaryDirectory() as d:
    # Stamp from beyond the freshness window so the gate actually reaches the
    # checker (a fresh stamp would legitimately skip it).
    record_healthy("/x/minis.db", 1024, d, NOW - FRESH_WINDOW_MS - 1)
    r = gate(True, "/x/minis.db", 1024, "corrupt page", d, NOW)
    check("failing scan invalidates + quarantines", r == "Quarantined" and read_stamp(d) is None)
with tempfile.TemporaryDirectory() as d:
    record_healthy("/x/minis.db", 1024, d, NOW)
    r = gate(False, "/x/gone.db", 0, None, d, NOW)
    check("missing db FreshInstall", r == "FreshInstall" and read_stamp(d) is None)

failed = [n for n, ok in checks if not ok]
for n, ok in checks:
    print(("PASS" if ok else "FAIL"), n)
print(f"{len(checks) - len(failed)}/{len(checks)} checks passed")
raise SystemExit(1 if failed else 0)
