# -*- coding: utf-8 -*-
"""Fetch the failed PR check's job log tail to find the first real error."""
import json
import os
import sys
import urllib.request

tok = os.environ["GITHUB_TOKEN"]
REPO = "tall-1997/OpenMinis-Linux"
BASE = f"https://api.github.com/repos/{REPO}"
SHA = "a5989da1cbeb795fdd27b6edfd5740f03e99675a"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


def get(url, auth=True, binary=False):
    h = {"User-Agent": "m", "Accept": "application/vnd.github+json"}
    if auth:
        h["Authorization"] = f"Bearer {tok}"
    req = urllib.request.Request(url, headers=h)
    try:
        r = opener.open(req, timeout=90)
        return r.read()
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307):
            return get(e.headers["Location"], auth=False)
        raise


runs = json.loads(get(f"{BASE}/actions/runs?head_sha={SHA}&per_page=20"))["workflow_runs"]
print("runs:", [(r["name"], r["conclusion"], r["id"]) for r in runs])
for r in runs:
    jobs = json.loads(get(f"{BASE}/actions/runs/{r['id']}/jobs?per_page=100"))["jobs"]
    for j in jobs:
        if j["conclusion"] in ("failure", "cancelled"):
            print(f"\n=== failed job: {j['name']} (id {j['id']}) ===")
            for s in j["steps"]:
                print(f"  [{s['conclusion'] or s['status']}] {s['name']}")
            log = get(f"{BASE}/actions/jobs/{j['id']}/logs").decode("utf-8", "replace")
            path = rf"D:\minis-worktrees\integration\.pr-ci-fail-{j['id']}.log"
            open(path, "w", encoding="utf-8").write(log)
            lines = log.splitlines()
            print(f"  log lines: {len(lines)} -> {path}")
            idxs = [i for i, l in enumerate(lines)
                    if "##[error]" in l or " FAILED" in l or "error:" in l or "e: " in l]
            shown = set()
            for i in idxs[:12]:
                for k in range(max(0, i - 4), min(len(lines), i + 8)):
                    if k not in shown:
                        shown.add(k)
                        print(lines[k])
