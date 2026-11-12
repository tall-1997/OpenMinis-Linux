# -*- coding: utf-8 -*-
"""Move the TAG constant after the import block in ProviderOAuthBlock.kt."""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderOAuthBlock.kt"
lines = open(p, encoding="utf-8").read().splitlines(keepends=True)

# drop the misplaced const line
lines = [l for l in lines if l.strip() != 'private const val TAG = "ProviderDetail"']
# find last import index
last_import = max(i for i, l in enumerate(lines) if l.startswith("import "))
lines.insert(last_import + 1, '\nprivate const val TAG = "ProviderDetail"\n')
open(p, "w", encoding="utf-8", newline="").write("".join(lines))
print("TAG moved after imports")
