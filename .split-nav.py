# -*- coding: utf-8 -*-
"""Extract trailing helpers from AppNavigation and the OAuth block from ProviderDetailScreen."""
base = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui"

# ---- AppNavigation: move HtmlBrowserHost + AppNavigationShareHost + previewLink ----
p1 = base + "/navigation/AppNavigation.kt"
lines = open(p1, encoding="utf-8").read().splitlines(keepends=True)

def idx(sub, start=0):
    for i in range(start, len(lines)):
        if sub in lines[i]:
            return i
    raise SystemExit("not found: " + sub)

start = idx("@Composable\n") if False else idx("private fun HtmlBrowserHost(")
# back up to the @Composable line
while start > 0 and not lines[start].strip().startswith("@Composable"):
    start -= 1
tail = "".join(lines[start:])
assert "previewLink" in tail
lines = lines[:start]
open(p1, "w", encoding="utf-8", newline="").write("".join(lines))
new1 = "package com.openminis.app.ui.navigation\n\n" + tail
open(base + "/navigation/AppNavigationHosts.kt", "w", encoding="utf-8", newline="").write(new1)

# ---- ProviderDetailScreen: move OAuthCredentialBlock ----
p2 = base + "/settings/ProviderDetailScreen.kt"
lines2 = open(p2, encoding="utf-8").read().splitlines(keepends=True)

def idx2(sub, start=0):
    for i in range(start, len(lines2)):
        if sub in lines2[i]:
            return i
    raise SystemExit("not found: " + sub)

s2 = idx2("private fun OAuthCredentialBlock(")
while s2 > 0 and not lines2[s2].strip().startswith("@Composable"):
    s2 -= 1
e2 = idx2("@Composable", s2 + 1)
oauth = "".join(lines2[s2:e2])
assert "OAuthCredentialBlock" in oauth
lines2 = lines2[:s2] + lines2[e2:]
open(p2, "w", encoding="utf-8", newline="").write("".join(lines2))
new2 = "package com.openminis.app.ui.settings\n\n" + oauth
open(base + "/settings/ProviderOAuthBlock.kt", "w", encoding="utf-8", newline="").write(new2)

print("AppNavigation now:", len("".join(lines).splitlines()), "| hosts:", len(new1.splitlines()))
print("ProviderDetailScreen now:", len("".join(lines2).splitlines()), "| oauth:", len(new2.splitlines()))
