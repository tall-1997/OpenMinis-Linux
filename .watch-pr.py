# -*- coding: utf-8 -*-
"""Poll PR #325 checks until complete (network-retrying). Exit 0=green, 1=failed, 2=timeout."""
import json
import os
import sys
import time
import urllib.request

tok = os.environ["GITHUB_TOKEN"]
REPO = "tall-1997/OpenMinis-Linux"
BASE = f"https://api.github.com/repos/{REPO}"
PR = 325

limit_s = int(sys.argv[1]) if len(sys.argv) > 1 else 1500
deadline = time.time() + limit_s


def get(url, retries=5):
    delay = 5
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={
                "Authorization": f"Bearer {tok}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "minis-agent",
            })
            return json.loads(urllib.request.urlopen(req, timeout=60).read())
        except Exception as e:
            if attempt == retries:
                raise
            print(f"  retry {attempt}: {type(e).__name__}", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 40)


pr = get(f"{BASE}/pulls/{PR}")
sha = pr["head"]["sha"]
print("PR#325 head:", sha[:8], "| mergeable:", pr.get("mergeable"), flush=True)

final = None
last_line = ""
while time.time() < deadline:
    runs = get(f"{BASE}/commits/{sha}/check-runs?per_page=100")["check_runs"]
    states = [(r["name"], r["status"], r["conclusion"]) for r in runs]
    pending = [s for s in states if s[1] != "completed"]
    line = f"checks={len(states)} pending={len(pending)} " + ", ".join(
        f"{n}:{c or s}" for n, s, c in states)
    if line != last_line:
        print(line, flush=True)
        last_line = line
    if not pending:
        final = states
        break
    time.sleep(30)

if final is None:
    print("TIMEOUT", flush=True)
    sys.exit(2)
failed = [s for s in final if s[2] in ("failure", "cancelled", "timed_out", "action_required")]
print("RESULT:", "FAILED:" + ",".join(f"{n}" for n, _, _ in failed) if failed else "GREEN", flush=True)
sys.exit(1 if failed else 0)
