# -*- coding: utf-8 -*-
"""Fix remaining split references: ShellDeathTail reset/headPreview, ShellLineFeeder.emitLine,
EmptyTurnRecovery import, HtmlBrowserState/maskedKey/OAuthCredentialBlock visibility."""
base = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app"

# --- ShellDeathTail: add reset + headPreview ---
p = base + "/sandbox/ShellDeathTail.kt"
s = open(p, encoding="utf-8").read()
if "fun reset()" not in s:
    s = s.replace(
        "    fun append(text: String) {",
        "    /** Fresh capture per process incarnation — a respawned shell must not\n"
        "     * report its predecessor's dying output as its own. */\n"
        "    fun reset() {\n"
        "        synchronized(tail) {\n"
        "            head.setLength(0)\n"
        "            tail.setLength(0)\n"
        "            total = 0\n"
        "        }\n"
        "    }\n\n"
        "    /** 启动结果日志里的早期输出预览（不超过 [max] 字符）。 */\n"
        "    fun headPreview(max: Int): String = synchronized(tail) { head.toString().take(max) }\n\n"
        "    fun append(text: String) {",
        1,
    )
open(p, "w", encoding="utf-8", newline="").write(s)

# --- ShellLineFeeder: expose emitLine ---
p = base + "/sandbox/ShellLineFeeder.kt"
s = open(p, encoding="utf-8").read()
s = s.replace("    private fun emit(callback: (String) -> Unit, line: String) {", "    internal fun emitLine(callback: (String) -> Unit, line: String) {")
s = s.replace("emit(callback, line)", "emitLine(callback, line)")
open(p, "w", encoding="utf-8", newline="").write(s)

# --- PersistentShell: replace the three remaining usages ---
p = base + "/sandbox/PersistentShell.kt"
s = open(p, encoding="utf-8").read()
pairs = [
    (
        "        synchronized(outputTail) {\n"
        "            outputHead.setLength(0)\n"
        "            outputTail.setLength(0)\n"
        "            outputTotal = 0\n"
        "        }\n",
        "        deathTailStore.reset()\n",
    ),
    (
        "val early = synchronized(outputTail) { outputHead.toString().take(300) }",
        "val early = deathTailStore.headPreview(300)",
    ),
    (
        "{ line -> emitLine(lineCallback, line) }",
        "{ line -> lineFeeder.emitLine(lineCallback, line) }",
    ),
]
for old, new in pairs:
    if old not in s:
        print("WARN PersistentShell pair not found:", old.splitlines()[0][:60])
        continue
    s = s.replace(old, new)
open(p, "w", encoding="utf-8", newline="").write(s)

# --- EmptyTurnRecoveryInject: same package, drop wrong import ---
p = base + "/ui/chat/EmptyTurnRecoveryInject.kt"
s = open(p, encoding="utf-8").read()
s = s.replace("import com.openminis.app.tools.EmptyTurnRecovery\n", "")
open(p, "w", encoding="utf-8", newline="").write(s)

# --- visibility fixes ---
p = base + "/ui/navigation/AppNavigation.kt"
s = open(p, encoding="utf-8").read()
s = s.replace("private class HtmlBrowserState", "internal class HtmlBrowserState")
open(p, "w", encoding="utf-8", newline="").write(s)

p = base + "/ui/settings/ProviderDetailScreen.kt"
s = open(p, encoding="utf-8").read()
s = s.replace("private fun maskedKey(", "internal fun maskedKey(")
open(p, "w", encoding="utf-8", newline="").write(s)

p = base + "/ui/settings/ProviderOAuthBlock.kt"
s = open(p, encoding="utf-8").read()
s = s.replace("private fun OAuthCredentialBlock(", "internal fun OAuthCredentialBlock(")
open(p, "w", encoding="utf-8", newline="").write(s)

print("all split-reference fixes applied")
