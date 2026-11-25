package com.openminis.app.ui.chat

import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject

/**
 * [T-ctx-trim-ladder] Brings an outbound history down to a safe watermark by
 * trimming in order of cost, re-measuring after every level and stopping as
 * soon as it fits.
 *
 * Why: a long agent run on a thinking model (DeepSeek, max) replays every past
 * turn's reasoning_content. Field run 2026-10-07 (sid 41295a4c): the request
 * reached 341,873 prompt tokens on a 128K window while the meter, which did not
 * count reasoning, read 146,874. Compaction kept triggering and freed nothing,
 * because the slice it keeps (the last 3 user turns) was the whole task and the
 * reasoning inside it was never touched.
 *
 * Levels (each applies only to iterations older than the protected tail):
 *  - L1  reasoning_content of older assistant turns emptied, keeping the last
 *        K (2 -> 1 -> 0). DeepSeek accepts the empty field.
 *  - L2  large tool results moved to disk, replaced by the offload stub.
 *  - L3  large tool arguments (file_write bodies ...) replaced by the
 *        placeholder notice the write tools refuse to reuse.
 *  - SK  "skeleton": older iterations keep the call and a one-line result.
 *  - L4  oldest iterations collapsed into an index line each (only when the
 *        caller allows it - after a model summary had its chance).
 *
 * The result is described by a [State] of cut-off keys (tool-call ids), not by
 * the trimmed list: re-applying the same State to a history that only grew
 * yields byte-identical output for the old part, so the provider's prefix cache
 * keeps hitting until the next re-plan.
 *
 * Pure apart from [Offloader], which the caller backs with real disk writes.
 */
internal object ContextTrimPlanner {

    // ── Watermarks ──────────────────────────────────────────────────────

    enum class Mode { NORMAL, TIGHT, UNUSABLE }

    /** All values in measured (calibrated) tokens. */
    data class Watermarks(
        val window: Int,
        val fixed: Int,
        val reserve: Int,
        val hard: Int,
        val usable: Int,
        val trigger: Int,
        val target: Int,
        val mode: Mode,
    )

    const val TRIGGER_SHARE = 0.90
    const val TARGET_SHARE = 0.50

    /**
     * Watermarks from the space history can really use: the window minus the
     * output reserve minus the fixed system prompt + tool schemas. A share of
     * the raw window does not work for small windows - with a ~21K prompt, half
     * of a 32K window is below the fixed cost itself.
     */
    fun watermarks(window: Int, fixed: Int, maxOutput: Int): Watermarks? {
        if (window <= 0) return null
        val reserve = minOf(maxOutput.coerceAtLeast(1), maxOf(4096, (window * 0.15).toInt()))
        val hard = window - reserve
        val usable = hard - fixed
        val trigger = fixed + (usable * TRIGGER_SHARE).toInt()
        val target = fixed + (usable * TARGET_SHARE).toInt()
        val mode = when {
            usable <= 0 -> Mode.UNUSABLE
            usable < maxOf(8192, (window * 0.25).toInt()) -> Mode.TIGHT
            else -> Mode.NORMAL
        }
        return Watermarks(window, fixed, reserve, hard, usable, trigger, target, mode)
    }

    /**
     * [T-ctx-trim-trigger-margin] Where the ladder re-plans: the tier's
     * compaction line when it has one, but never above the watermark trigger
     * (fixed + 0.9 x usable), which sits a tenth of the usable space below
     * [Watermarks.hard].
     *
     * The line alone leaves no margin, or is past the hard watermark:
     *  - a capped group compacts at 85% of its window and the output reserve
     *    is 15%, so line == hard (128K: both 108,800);
     *  - a native window of 128K or more compacts at window - 20K, which is
     *    ABOVE hard (1M: line 980,000, hard 850,000 or higher) - the ladder
     *    did nothing until the request no longer left room for the answer.
     * A lower line still wins; no line (small windows) uses the watermark.
     */
    fun trigger(policyLine: Int?, watermarks: Watermarks): Int =
        policyLine?.takeIf { it > 0 }?.let { minOf(it, watermarks.trigger) } ?: watermarks.trigger

    /**
     * [T-ctx-trim-escalate] Whether a plan that could NOT collapse won back so
     * little that a summary / collapse should be tried now: it ended above the
     * midpoint between target and trigger, i.e. recovered less than half the
     * room. A small miss does not escalate.
     *
     * Needed since the trigger sits below the compaction line: such a plan
     * keeps the request just under that line, the compaction check never
     * fires, and the loop re-plans every few requests for less and less (iOS
     * on-device: 100,967 -> 83,919, -> 89,148, -> 91,946, three minutes apart,
     * each one changing the request prefix).
     */
    fun shouldEscalate(after: Int, target: Int, trigger: Int): Boolean =
        after > target + (trigger - target) / 2

    /**
     * [T-ctx-trim-plan-window] Whether a frozen plan made for [planWindow]
     * still belongs to the window the next request is judged by. A plan is
     * sized for one window: its target is half of THAT window's usable space,
     * so keeping it after the window changed applies the wrong amount of trim.
     *
     * iOS on-device 2026-10-07: a plan made under a 128K cap (reasoning emptied
     * on 343 of 345 iterations, 179 collapsed) stayed in force after the
     * session moved to a 1M window. Nothing re-planned, because the trimmed
     * request was far below the new trigger - 560 steps went out as 81K of 1M.
     */
    fun planFitsWindow(planWindow: Int?, window: Int): Boolean = planWindow == window

    /**
     * [T-ctx-trim-plan-marker] Whether a frozen plan made on top of the
     * compaction marker [planMarkerId] (null = no compaction) still belongs to
     * the history the next request is built from. A compaction or a revert
     * replaces what the plan was sized for. The plan's cut-off keys do not
     * always notice: they usually name RECENT iterations, which a compaction
     * keeps, so the old extents went on being applied to the kept slice of a
     * history that had just become small.
     */
    fun planFitsMarker(planMarkerId: String?, markerId: String?): Boolean = planMarkerId == markerId

    // ── Plan state ──────────────────────────────────────────────────────

    /**
     * Prefix of a cut-off that INCLUDES the named iteration ("up to and
     * including"). Used when a level covers every iteration that existed at
     * plan time: iterations added later must stay untouched until the next
     * re-plan, which a plain "all" would not guarantee.
     */
    const val THROUGH = "<="

    /**
     * Each field names where that level stops: the key of the first iteration
     * NOT affected, or [THROUGH] + the key of the last one affected
     * (null = level off).
     */
    data class State(
        val reasoningBefore: String? = null,
        val shrinkBefore: String? = null,
        val skeletonBefore: String? = null,
        val collapseBefore: String? = null,
    ) {
        val isEmpty: Boolean
            get() = reasoningBefore == null && shrinkBefore == null && skeletonBefore == null && collapseBefore == null
    }

    /**
     * [reasoningFloor] newest iterations whose reasoning L1 never empties (1 in
     * normal mode: the reasoning behind the step in flight; 0 in tight mode).
     * [keepRecentIterations] newest iterations L2/L3, skeleton and collapse
     * never touch.
     */
    data class Options(
        val stripReasoning: Boolean,
        val reasoningFloor: Int = 1,
        val keepRecentIterations: Int = 3,
        val allowCollapse: Boolean = false,
    )

    data class Result(
        val state: State,
        val messages: List<LLMMessage>,
        val before: Int,
        val after: Int,
        val target: Int,
        val steps: List<String>,
    ) {
        val reachedTarget: Boolean get() = after <= target
    }

    /** Writes [content] to disk and returns its guest path, or null when it could not. */
    fun interface Offloader {
        fun offload(content: String, toolId: String, toolName: String): String?
    }

    const val SHRINK_CHARS = 2_000
    const val SKELETON_CHARS = 200
    const val INDEX_MAX_LINES = 60

    // ── Iterations ──────────────────────────────────────────────────────

    /**
     * One iteration: an assistant message plus the user-role messages that
     * carry ITS tool results. [pinned] when one of those carriers also holds
     * text (a compaction summary appended after tool results): it can be
     * shortened but never removed.
     */
    internal data class Iteration(val key: String, val assistantIdx: Int, val carrierIdx: List<Int>, val pinned: Boolean)

    internal fun iterations(messages: List<LLMMessage>): List<Iteration> {
        val out = mutableListOf<Iteration>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.role != LLMMessage.Role.ASSISTANT) { i++; continue }
            val useIds = m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
            val key = useIds.firstOrNull()?.let { m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().first().id }
                ?: "msg:${m.dbMessageId ?: "$i:${m.content.hashCode()}"}"
            val carriers = mutableListOf<Int>()
            var pinned = false
            var j = i + 1
            while (useIds.isNotEmpty() && j < messages.size) {
                val c = messages[j]
                if (c.role != LLMMessage.Role.USER) break
                val results = c.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                if (results.isEmpty() || results.none { it.id in useIds }) break
                carriers += j
                if (c.contentParts.any { it !is AgentContentPart.ToolResult }) pinned = true
                j++
            }
            out += Iteration(key, i, carriers, pinned)
            i = maxOf(i + 1, j)
        }
        return out
    }

    /** Position of [key] among [its], or null when a stored key no longer exists. */
    private fun cut(key: String?, its: List<Iteration>): Int? = when {
        key == null -> 0
        key.startsWith(THROUGH) -> its.indexOfFirst { it.key == key.removePrefix(THROUGH) }.takeIf { it >= 0 }?.plus(1)
        else -> its.indexOfFirst { it.key == key }.takeIf { it >= 0 }
    }

    /** Key that affects iterations [0, n) of [its] - and no iteration added later. */
    internal fun keyFor(n: Int, its: List<Iteration>): String? = when {
        n <= 0 || its.isEmpty() -> null
        n >= its.size -> THROUGH + its.last().key
        else -> its[n].key
    }

    // ── Apply ───────────────────────────────────────────────────────────

    /** Re-applies [state]; null when one of its keys is gone (the caller re-plans). */
    fun apply(messages: List<LLMMessage>, state: State, offloader: Offloader): List<LLMMessage>? {
        if (state.isEmpty) return messages
        val its = iterations(messages)
        val rB = cut(state.reasoningBefore, its) ?: return null
        val shB = cut(state.shrinkBefore, its) ?: return null
        val skB = cut(state.skeletonBefore, its) ?: return null
        val cB = cut(state.collapseBefore, its) ?: return null

        val iterOf = HashMap<Int, Int>()
        its.forEachIndexed { n, it -> iterOf[it.assistantIdx] = n; it.carrierIdx.forEach { c -> iterOf[c] = n } }

        val collapsed = mutableListOf<Iteration>()
        val out = ArrayList<LLMMessage>(messages.size)
        var indexHostAt = -1
        for ((idx, m) in messages.withIndex()) {
            val n = iterOf[idx]
            if (n == null) {
                out += m
                // The index lands on the last plain user message before the collapsed range.
                if (m.role == LLMMessage.Role.USER && n == null && collapsed.isEmpty()) indexHostAt = out.size - 1
                continue
            }
            val it = its[n]
            if (n < cB && !it.pinned) {
                if (idx == it.assistantIdx) collapsed += it
                continue
            }
            out += transform(m, n, rB, shB, skB, offloader)
        }
        if (collapsed.isEmpty()) return out
        val index = indexText(collapsed.map { messages[it.assistantIdx] to it.carrierIdx.map { c -> messages[c] } })
        return if (indexHostAt >= 0) {
            val host = out[indexHostAt]
            out[indexHostAt] = host.copy(
                content = host.content + "\n\n" + index,
                contentParts = if (host.contentParts.isEmpty()) host.contentParts else host.contentParts + AgentContentPart.Text(index),
            )
            out
        } else {
            out.apply { add(0, LLMMessage(role = LLMMessage.Role.USER, content = index, contentParts = listOf(AgentContentPart.Text(index)))) }
        }
    }

    private fun transform(m: LLMMessage, n: Int, rB: Int, shB: Int, skB: Int, offloader: Offloader): LLMMessage {
        val skeleton = n < skB
        val shrink = n < shB
        var msg = m
        if (n < rB && m.role == LLMMessage.Role.ASSISTANT && !m.reasoningContent.isNullOrEmpty()) {
            msg = msg.copy(reasoningContent = "")
        }
        if (!skeleton && !shrink) return msg
        // Levels stack: a skeleton is built on top of the shrunk part, so it is
        // never larger than what shrink alone produced.
        val parts = msg.contentParts.map { part ->
            when (part) {
                is AgentContentPart.ToolUse -> {
                    val shrunk = if (shrink) shortenArgs(part, SHRINK_CHARS, offloader) else part
                    if (skeleton) shortenArgs(shrunk, SKELETON_CHARS, offloader) else shrunk
                }
                is AgentContentPart.ToolResult -> {
                    val shrunk = if (shrink) shrinkResult(part, offloader) else part
                    if (skeleton) skeletonResult(shrunk, offloader) else shrunk
                }
                else -> part
            }
        }
        return if (parts == msg.contentParts) msg else msg.copy(contentParts = parts)
    }

    private fun isStub(text: String) =
        text.trimStart().startsWith(ContextOffload.OFFLOADED_PREFIX) || ContextOffload.isOffloadPlaceholder(text)

    private fun approxTokens(text: String) = com.openminis.app.data.ContextSizeMeter.estimateTokens(text)

    private fun shrinkResult(part: AgentContentPart.ToolResult, offloader: Offloader): AgentContentPart.ToolResult {
        if (part.content.length <= SHRINK_CHARS || isStub(part.content)) return part
        val bytes = part.content.toByteArray().size
        val path = offloader.offload(part.content, part.id, part.name)
        val text = if (!path.isNullOrEmpty()) ContextOffload.stub(approxTokens(part.content), bytes, path)
        else part.content.take(SHRINK_CHARS / 2) + "\n… [${part.content.length - SHRINK_CHARS / 2} chars omitted from context]"
        return part.copy(content = text)
    }

    private fun skeletonResult(part: AgentContentPart.ToolResult, offloader: Offloader): AgentContentPart.ToolResult {
        val noImage = part.copy(imageData = null, imageMimeType = null)
        if (part.content.length <= SKELETON_CHARS + 100 && part.imageData == null) return part
        // An offload stub is already as small as a skeleton; rewriting it would only grow it.
        if (isStub(part.content)) return if (part.imageData == null) part else noImage
        val path = if (isStub(part.content)) {
            Regex("""saved to: (\S+)""").find(part.content)?.groupValues?.get(1)
        } else {
            offloader.offload(part.content, part.id, part.name)
        }
        val first = part.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith(ContextOffload.OFFLOADED_PREFIX) }
            ?.take(SKELETON_CHARS).orEmpty()
        val where = if (!path.isNullOrEmpty()) "full output: $path" else "full output kept in the chat history"
        val image = if (part.imageData != null) " (image omitted)" else ""
        val text = "$first\n[shortened for context$image; $where]"
        if (text.length >= part.content.length) return if (part.imageData == null) part else noImage
        return noImage.copy(content = text)
    }

    private fun shortenArgs(part: AgentContentPart.ToolUse, limit: Int, offloader: Offloader): AgentContentPart.ToolUse {
        val input = part.input
        var changed = false
        val copy = JSONObject(input.toString())
        for (k in input.keys()) {
            val v = input.opt(k) as? String ?: continue
            if (v.length <= limit || ContextOffload.isOffloadPlaceholder(v)) continue
            val notice = "${ContextOffload.SYSTEM_REMINDER_OPEN}\n${ContextOffload.SYSTEM_NOTICE_TAG} Argument shortened for context " +
                "(${v.length} chars). NOT the real value - never reuse it. Starts with: «${v.take(120)}»\n</system-reminder>"
            // The notice is ~330 chars: on a short value it would GROW the
            // request (field 2026-10-07: 25 of 71 shortened shell commands).
            if (notice.length >= v.length) continue
            val path = if (limit >= SHRINK_CHARS) offloader.offload(v, part.id, "args-${part.name}") else null
            copy.put(
                k,
                if (!path.isNullOrEmpty()) ContextOffload.prunedArgumentNotice(approxTokens(v), v.toByteArray().size, path) else notice,
            )
            changed = true
        }
        return if (changed) part.copy(input = copy, isOffloadedArgument = true) else part
    }

    /** Index of collapsed iterations, newest [INDEX_MAX_LINES] listed, the rest counted. */
    internal fun indexText(collapsed: List<Pair<LLMMessage, List<LLMMessage>>>): String {
        val lines = mutableListOf<String>()
        val counts = linkedMapOf<String, Int>()
        for ((n, pair) in collapsed.withIndex()) {
            val (asst, carriers) = pair
            val results = carriers.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolResult>() }.associateBy { it.id }
            val uses = asst.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (uses.isEmpty()) {
                val note = asst.content.ifBlank { asst.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString(" ") { it.text } }
                if (note.isNotBlank()) lines += "#${n + 1} note: ${note.lineSequence().first().take(100)}"
                continue
            }
            for (u in uses) {
                counts[u.name] = (counts[u.name] ?: 0) + 1
                val title = u.input.optString("tool_title").ifBlank { u.input.optString("title") }.take(60)
                val r = results[u.id]
                val status = when { r == null -> "no result"; r.isError -> "error"; else -> "ok" }
                val path = r?.content?.let { Regex("""saved to: (\S+)""").find(it)?.groupValues?.get(1) }
                lines += "#${n + 1} ${u.name}${if (title.isNotBlank()) " «$title»" else ""} → $status${path?.let { " ($it)" } ?: ""}"
            }
        }
        val shown = lines.takeLast(INDEX_MAX_LINES)
        val hidden = lines.size - shown.size
        val summary = counts.entries.joinToString(", ") { "${it.key} ${it.value}" }
        return buildString {
            append(ContextOffload.SYSTEM_REMINDER_OPEN).append('\n')
            append("[Minis Ultra context] ${collapsed.size} earlier step(s) of this task were collapsed to save context. ")
            append("Their full records stay in the chat history; large outputs are on disk at the paths shown. ")
            append("Do not redo a step listed as ok - read its output path if you need the details.\n")
            append("Tool calls collapsed: $summary\n")
            if (hidden > 0) append("($hidden older line(s) not listed)\n")
            shown.forEach { append(it).append('\n') }
            append("</system-reminder>")
        }
    }

    // ── Plan ────────────────────────────────────────────────────────────

    private class Level(val label: String, val max: Int, val set: (State, String?) -> State)

    /**
     * Cheapest level first. Each level affects the SMALLEST oldest-first
     * prefix of iterations that reaches [target] (binary search, re-measuring
     * every candidate); only a level that cannot reach it alone runs at full
     * extent and hands over to the next one. A restore pass then shrinks the
     * earlier, full-extent levels back as far as the result still fits - so
     * as much context as possible survives. [measure] returns measured tokens
     * for a history (fixed cost included).
     */
    fun plan(
        messages: List<LLMMessage>,
        target: Int,
        options: Options,
        measure: (List<LLMMessage>) -> Int,
        offloader: Offloader,
    ): Result {
        val its = iterations(messages)
        val before = measure(messages)
        val steps = mutableListOf<String>()
        if (before <= target || its.isEmpty()) return Result(State(), messages, before, before, target, steps)

        val older = (its.size - options.keepRecentIterations.coerceIn(0, its.size)).coerceAtLeast(0)
        val levels = listOf(
            Level("L1", if (options.stripReasoning) (its.size - options.reasoningFloor.coerceAtLeast(0)).coerceAtLeast(0) else 0) { s, k -> s.copy(reasoningBefore = k) },
            Level("L2L3", older) { s, k -> s.copy(shrinkBefore = k) },
            Level("SK", older) { s, k -> s.copy(skeletonBefore = k) },
            Level("L4", if (options.allowCollapse) older else 0) { s, k -> s.copy(collapseBefore = k) },
        )
        val extent = IntArray(levels.size)
        fun stateOf(ext: IntArray) = levels.indices.fold(State()) { s, i -> levels[i].set(s, keyFor(ext[i], its)) }
        val memo = HashMap<List<Int>, Pair<List<LLMMessage>, Int>>()
        fun eval(ext: IntArray): Pair<List<LLMMessage>, Int> = memo.getOrPut(ext.toList()) {
            val applied = apply(messages, stateOf(ext), offloader) ?: messages
            applied to measure(applied)
        }
        /** Smallest n in [lo, hi] for level [li] that fits, others as in [extent]; assumes hi fits. */
        fun smallest(li: Int, lo: Int, hi: Int): Int {
            var a = lo
            var b = hi
            while (a < b) {
                val mid = (a + b) / 2
                val e = extent.copyOf().also { it[li] = mid }
                if (eval(e).second <= target) b = mid else a = mid + 1
            }
            return b
        }
        fun label(li: Int) = "${levels[li].label}(n=${extent[li]}/${its.size})"

        var size = before
        var last = -1
        for (li in levels.indices) {
            val lv = levels[li]
            if (lv.max <= 0) continue
            last = li
            val full = extent.copyOf().also { it[li] = lv.max }
            val fullSize = eval(full).second
            // A level with nothing to take here (no reasoning, no large output) is skipped, not logged.
            if (fullSize >= size) continue
            if (fullSize > target) {
                extent[li] = lv.max
                steps += "${label(li)}:${size - fullSize}"
                size = fullSize
                continue
            }
            extent[li] = smallest(li, 1, lv.max)
            val m = eval(extent).second
            steps += "${label(li)}:${size - m}"
            size = m
            break
        }
        // Restore: the level that closed the gap may have freed more than the
        // earlier ones needed to give up. Most informative first: skeleton,
        // then offload, then reasoning.
        if (size <= target && last > 0) {
            for (li in (0 until last).reversed()) {
                if (extent[li] == 0) continue
                val was = extent[li]
                val zero = extent.copyOf().also { it[li] = 0 }
                extent[li] = if (eval(zero).second <= target) 0 else smallest(li, 0, was)
                if (extent[li] == was) continue
                val m = eval(extent).second
                steps += "${levels[li].label}-restore(n=${extent[li]}/${its.size}):${size - m}"
                size = m
            }
        }
        val (current, after) = eval(extent)
        return Result(stateOf(extent), current, before, after, target, steps)
    }
}
