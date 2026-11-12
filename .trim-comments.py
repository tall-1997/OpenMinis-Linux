# -*- coding: utf-8 -*-
"""Trim verbose comments added by our batches in AgentLoopExt to meet the file line ceiling.

Comments only — no code lines change. The batch-tag prefixes stay so the issue
linkage remains greppable.
"""
p = r"D:/minis-worktrees/integration/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt"
s = open(p, encoding="utf-8").read()

pairs = [
    # #221 header channel
    (
        "                // [GH#221] 用户自定义头 + OpenCode 会话头统一走同一通道。\n"
        "                val requestHeaders = requestExtraHeaders()\n",
        "                // [GH#221] 用户头 + OpenCode 会话头走同一通道。\n"
        "                val requestHeaders = requestExtraHeaders()\n",
    ),
    # #563 date refresh
    (
        "                // [GH#563] Re-stamp the runtime-context date before every request\n"
        "                // of this run. A long agent loop (or a queued prompt drained\n"
        "                // hours later) otherwise kept the date of the turn that started\n"
        "                // the loop, so \"today/tomorrow\" answers drifted. The date sits\n"
        "                // in the dynamic tail (after systemPromptStablePrefixLen), so\n"
        "                // this does not disturb the cached prefix.\n",
        "                // [GH#563] 每次请求前重刷日期；日期在动态尾部，不扰动缓存前缀。\n",
    ),
    # #61 rawArgs: declaration
    (
        "    // [GH#61/#83/#146] Provider-reported unparseable argument tails for this\n"
        "    // stream attempt, keyed by the same deduped id toolCalls uses. Run-scoped\n"
        "    // storage (so the bubble-swap helper can clear it), reset at every turn.\n",
        "    // [GH#61/#83/#146] 本轮无法解析的参数尾巴（keyed by toolCall id，每轮清空）。\n",
    ),
    # #61 rawArgs: per-chunk capture
    (
        "                    // [GH#61/#83/#146] Keep the exact unparsed tail next to\n"
        "                    // the call. The parsed `args` above is `{}` in this case;\n"
        "                    // ToolJsonRepair/preflight must see the bytes that failed\n"
        "                    // instead of treating it as a genuine no-argument call.\n",
        "                    // [GH#61/#83/#146] 保留真实失败字节，供 preflight 修复/拒绝。\n",
    ),
    # #521 watchdog params
    (
        "                    // [GH#521] Distinct headers→first-token phase. `Started`\n"
        "                    // satisfies the phase-1 budget (HTTP headers are in), so\n"
        "                    // without this the only bound after headers was the 2×\n"
        "                    // idle budget (90-180s) and a 15-28s silent gateway looked\n"
        "                    // healthy.\n"
        "                    // 中文：headers 到达后单独看首 token，超时走 transient 重试。\n",
        "                    // [GH#521] headers 之后单独计首 token；否则 15-28s 静默网关会被 2× idle 掩盖。\n",
    ),
]
for old, new in pairs:
    if old not in s:
        print("WARN not found:", old.splitlines()[0][:70])
        continue
    s = s.replace(old, new)

open(p, "w", encoding="utf-8", newline="").write(s)
print("trimmed comments; splitlines now:", len(s.splitlines()))
