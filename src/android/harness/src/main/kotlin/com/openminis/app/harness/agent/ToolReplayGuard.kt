package com.openminis.app.harness.agent

/**
 * [T-android-duplicate-toolcall-replay] Idempotence guard for tool-call
 * dispatch keyed on the RAW upstream tool_use id.
 *
 * Why this exists: a buggy gateway / SSE replay layer can re-emit the same
 * completed tool call (same id, same name, same arguments) within one stream
 * attempt. The per-attempt id renamer ([T-dedupe-toolcallid]) handles the
 * *receiver* uniqueness contract by renaming the second occurrence to
 * `<id>-2`, but the renamed entry is a brand-new id to the execution loop —
 * so the replayed call would be dispatched a SECOND time: the tool runs
 * twice, two tool results enter the transcript, and the model sees duplicated
 * context (observed live 2026-10-04 as a duplicated analysis verdict inside
 * one assistant bubble).
 *
 * Contract, deliberately narrower than the renamer's:
 *  - Same raw id + same name + same serialized args  -> replay. Dispatch
 *    exactly once; every later occurrence is a duplicate (the caller
 *    synthesizes a refusal result so tool_use/tool_result pairing stays
 *    balanced without re-executing).
 *  - Same raw id but different name or args          -> the documented
 *    parallel-same-id gateway case. NOT a replay; the caller executes it
 *    under the renamed id exactly as before this guard existed.
 *  - Different raw id, identical name/args           -> a legitimate repeated
 *    call (the model often re-issues the same command). Always dispatches.
 *
 * State is per stream attempt: the agent loop constructs a fresh instance
 * alongside its other per-attempt accumulators, and clears it on the retry
 * rollback path together with `toolCalls` (a retried attempt re-streams into
 * an empty dispatch list, so nothing executed in the dead attempt can leak
 * into the guard).
 */
class ToolReplayGuard {

    private val seen = HashMap<String, String>()

    /**
     * Record a completed call and report whether it is a replay of one this
     * guard already saw for the same raw id. Call exactly once per
     * ToolCallComplete, keyed on the RAW upstream id (before any `-2` rename).
     *
     * @return true when [name]/[argsJson] for [rawId] were already completed
     *   (replay — do not dispatch); false when this is a first sighting or a
     *   legitimately different call sharing the id (dispatch under the
     *   renamed id).
     */
    fun registerAndCheckReplay(rawId: String, name: String, argsJson: String): Boolean {
        if (rawId.isEmpty()) return false
        // [T-p2-replay-fingerprint-keyorder] 指纹基于**键序归一**后的参数串：
        // JSON 对象无序，网关重发时若重排键序，原始序列化串的指纹就不同——
        // 漏判重放 → 工具双跑。ToolCallFingerprint.stableJson 在每层嵌套按
        // 字典序排键（ToolLoopDetector 同源归一），解析失败回退原始串（保持
        // 与旧实现的兼容面）。
        val canonicalArgs = runCatching {
            ToolCallFingerprint.stableJson(org.json.JSONObject(argsJson))
        }.getOrDefault(argsJson)
        val fingerprint = "$name\u0000$canonicalArgs"
        val previous = seen.put(rawId, fingerprint)
        return previous == fingerprint
    }

    /** Number of distinct raw ids observed. Diagnostics only. */
    fun observedRawIds(): Int = seen.size

    /** Drop all recorded sightings. Called on the retry-rollback paths so a
     *  fresh stream attempt starts with a clean dispatch bookkeeping. */
    fun reset() = seen.clear()

    /**
     * [T-android-seam-extraction] 循环片段：重放拒绝的 tool_result 部件构造
     * （纯半边）。宿主在派发循环里命中 [registerAndCheckReplay] 的 replay
     * 判定后调用本函数拿配对部件——文案逐字保留（给模型的指令：用已记录
     * 的结果继续，别重试同一调用）。
     */
    fun duplicateRefusal(toolCallId: String, toolName: String): com.openminis.app.data.model.AgentContentPart.ToolResult {
        val message = "Error: duplicate tool call ignored. This exact call " +
            "(same id, name, and arguments) was already dispatched earlier in this " +
            "turn and its result is already available above. The duplicate was not " +
            "executed. Continue with the recorded result."
        return com.openminis.app.data.model.AgentContentPart.ToolResult(
            id = toolCallId,
            name = toolName,
            content = message,
            isError = true,
        )
    }
}
