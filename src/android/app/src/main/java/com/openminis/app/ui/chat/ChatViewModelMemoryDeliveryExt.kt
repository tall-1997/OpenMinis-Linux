package com.openminis.app.ui.chat

import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.MemoryAssembly
import com.openminis.app.data.repository.MemoryDeliverySpan
import com.openminis.app.data.repository.MemoryEntryRef
import com.openminis.app.data.repository.MemoryIndex
import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.data.repository.MemoryPromptBudget
import com.openminis.app.data.repository.MemoryRecallEngine
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.MemoryUsageLedger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.sandbox.SessionWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MemoryDeliveryPrompt(
    val prompt: String,
    val daily: DailyMemoryDelivery?,
    val stablePrefixLen: Int = -1,
)

/** An absolute, half-open UTF-16 range in the same build that emitted [prompt]. */
internal data class DailyMemoryDelivery(
    private val prompt: String,
    val start: Int,
    val end: Int,
    val sessionId: String,
    val memoryOwnerId: String,
    val standing: List<MemoryPromptBudget.Fragment>,
    val spans: List<MemoryDeliverySpan>,
    val residentSources: Set<String>,
    val stablePrefixLen: Int = -1,
    /** Only an attempt-time projection can be credited. TTL is evaluated at dispatch, not response time. */
    val projectedAt: Long? = null,
) {
    private fun matchesForProjection(candidate: String): Boolean {
        if (start < 0 || end < start || end > prompt.length || end > candidate.length) return false
        if (candidate == prompt) return true
        // Runtime date is the only supported edit to an existing build before projection.
        val marker = "- Current date: "
        val dateStart = prompt.lastIndexOf(marker).takeIf { it >= end }?.plus(marker.length) ?: return false
        val dateEnd = prompt.indexOf(' ', dateStart).takeIf { it >= dateStart } ?: return false
        val candidateEnd = candidate.indexOf(' ', dateStart).takeIf { it >= dateStart } ?: return false
        if (!candidate.substring(dateStart, candidateEnd).matches(isoRuntimeDay)) return false
        return candidate.regionMatches(0, prompt, 0, dateStart) &&
            candidate.length - candidateEnd == prompt.length - dateEnd &&
            candidate.regionMatches(candidateEnd, prompt, dateEnd, prompt.length - dateEnd)
    }

    fun prefixed(prefix: String, wrapped: String): DailyMemoryDelivery = copy(
        prompt = wrapped, start = start + prefix.length, end = end + prefix.length,
        spans = spans.map { it.shifted(prefix.length) },
        stablePrefixLen = shiftedStablePrefix(stablePrefixLen, prefix.length),
    )

    /** The runtime date lives AFTER the slot. No searching for duplicate text or old fragments. */
    fun project(systemPrompt: String, block: String, allocation: MemoryAssembly.Result?, now: Long): MemoryDeliveryPrompt? {
        if (start < stablePrefixLen || !matchesForProjection(systemPrompt)) return null
        val replacement = systemPrompt.replaceRange(start, end, block)
        return MemoryDeliveryPrompt(replacement, copy(
            prompt = replacement,
            end = start + block.length,
            spans = allocation?.deliverySpans.orEmpty().map { it.shifted(start + DAILY_SEPARATOR.length) },
            residentSources = allocation.residentSources(),
            projectedAt = now,
        ), stablePrefixLen)
    }

    val entryRefs: Set<MemoryEntryRef> get() = spans.mapTo(HashSet()) { it.ref }

    fun deliveredIn(actual: String?): Set<MemoryEntryRef> =
        if (projectedAt != null && actual == prompt) entryRefs else emptySet()
}

private val isoRuntimeDay = Regex("\\d{4}-\\d{2}-\\d{2}")
private const val DAILY_SEPARATOR = "\n\n"

private data class PromptDisclosures(val sessionId: String, val skills: String?, val mcp: String?)
private val lastDisclosures = java.util.WeakHashMap<ChatViewModel, PromptDisclosures>()

/** Estimate from the published disclosure; cold skill/MCP loaders can write config or SQLite. */
internal fun ChatViewModel.promptDisclosures(publish: Boolean): Pair<String?, String?> {
    val session = activeSessionId
    if (!publish) {
        val cached = synchronized(lastDisclosures) { lastDisclosures[this] }?.takeIf { it.sessionId == session }
        return cached?.skills to cached?.mcp
    }
    val skills = skillRepository?.let {
        it.reloadFromDisk()
        com.openminis.app.data.repository.SkillManifest.sessionFragment(it, session, context)
    }
    mcpRepository?.reloadFromDisk()
    val mcp = mcpRepository?.mcpPromptFragment(session)
    synchronized(lastDisclosures) { lastDisclosures[this] = PromptDisclosures(session, skills, mcp) }
    return skills to mcp
}

internal fun dailyMemorySlot(text: String?): String = if (text.isNullOrBlank()) "" else DAILY_SEPARATOR + text

internal fun MemoryAssembly.Result?.residentSources(): Set<String> =
    this?.admittedDailyDates?.mapTo(HashSet()) { "$it.md" }.orEmpty()

/** Shared by initial assembly and attempt-time refresh, using one lifecycle clock per build. */
internal fun assembleDailyMemory(
    session: MemoryRepository,
    sessionDir: java.io.File,
    sharedDir: java.io.File,
    query: String?,
    standing: List<MemoryPromptBudget.Fragment>,
    enabled: Boolean,
    now: Long,
    log: Boolean = false,
): Pair<MemoryAssembly.Result, com.openminis.app.data.repository.MemoryFragmentBuilder.DailyInjection?> {
    val daily = if (enabled) session.loadRecentDailyFragments(query, now, log) else null
    val assembly = MemoryAssembly.assembleTracked(
        standing = standing,
        daily = daily?.fragments.orEmpty(),
        header = MemoryAssembly.DAILY_BLOCK_HEADER,
    ) { excludeDates ->
        if (!enabled) null else MemoryIndex.appendBudgetNote(
            MemoryIndex.fragmentWithEntries(sessionDir, sharedDir, excludeDates, now = now),
            daily?.budgetNote,
        )
    }
    return assembly to daily
}

internal fun ChatViewModel.buildMemoryDeliveryPrompt(publish: Boolean = true): MemoryDeliveryPrompt =
    assembleSystemPrompt(publish).delivery

internal fun ChatViewModel.wrapMemoryDeliveryPromptForOAuth(
    provider: LLMProvider,
    build: MemoryDeliveryPrompt,
    publish: Boolean = true,
): MemoryDeliveryPrompt {
    val identifier = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
    if ((provider as? AnthropicProvider)?.isOAuth != true || build.prompt.startsWith(identifier)) return build
    val prefix = "$identifier\n\n"
    val wrapped = if (publish) wrapSystemPromptForOAuth(provider, build.prompt) ?: return build else prefix + build.prompt
    return MemoryDeliveryPrompt(wrapped, build.daily?.prefixed(prefix, wrapped), shiftedStablePrefix(build.stablePrefixLen, prefix.length))
}

private fun shiftedStablePrefix(value: Int, offset: Int): Int = if (value < 0) value else value + offset

internal fun ChatViewModel.projectMemoryDeliveryPrompt(
    build: MemoryDeliveryPrompt,
    publish: Boolean = false,
    query: String? = liveRecallInput()?.query,
): MemoryDeliveryPrompt {
    val refreshed = refreshRuntimeContextDate(build.prompt, java.time.LocalDate.now().toString()) ?: build.prompt
    return projectMemoryDeliveryPrompt(refreshed, build.daily, publish, query)?.copy(stablePrefixLen = build.stablePrefixLen)
        ?: build
}

/** Read-only memory projection. [publish] only enables diagnostics on an actual request path. */
internal fun ChatViewModel.projectMemoryDeliveryPrompt(
    systemPrompt: String?,
    daily: DailyMemoryDelivery?,
    publish: Boolean = false,
    query: String? = liveRecallInput()?.query,
): MemoryDeliveryPrompt? {
    val prompt = systemPrompt ?: return null
    if (daily == null || daily.sessionId != activeSessionId) return MemoryDeliveryPrompt(prompt, null)
    val now = System.currentTimeMillis()
    if (!_memoryEnabled.value) return daily.project(prompt, "", null, now) ?: MemoryDeliveryPrompt(prompt, null)
    val sessionDir = SessionWorkspace.memoryDir(context.filesDir, daily.memoryOwnerId)
    val (assembly, _) = assembleDailyMemory(
        session = MemoryRepository(sessionDir),
        sessionDir = sessionDir,
        sharedDir = MemoryIndex.sharedDir(context.filesDir),
        query = query,
        standing = daily.standing,
        enabled = true,
        now = now,
        log = publish,
    )
    val text = com.openminis.app.data.repository.MemoryBlockSplit.split(assembly.allocation).transient
    return daily.project(prompt, dailyMemorySlot(text), assembly, now) ?: MemoryDeliveryPrompt(prompt, null)
}

/** Request-local, replaceable recall. A preview never marks an announcement as delivered. */
internal data class RecallReminder(
    val framed: String,
    val hits: List<MemoryRecallEngine.RecallHit>,
    val sessionId: String,
    val projectedAt: Long,
    val turnId: String? = null,
) {
    fun deliveredIn(actual: List<LLMMessage>): Boolean = actual.any { carrier ->
        carrier.role == LLMMessage.Role.USER && if (carrier.contentParts.isEmpty()) {
            // Other host reminders may follow this carrier or be appended to its text.
            "\n\n$framed\n\n" in "\n\n${carrier.content}\n\n"
        } else carrier.contentParts.any { it is AgentContentPart.Text && it.text == framed }
    }
}

internal fun List<LLMMessage>.withMemoryRecallReminder(recall: RecallReminder?): List<LLMMessage> {
    if (recall == null) return this
    val index = indexOfLast { it.role == LLMMessage.Role.USER }
    if (index < 0) return this
    // Keep tool pairs and partial assistant tails intact; this puts text after any tool_result parts.
    return toMutableList().apply { this[index] = appendUserText(this[index], recall.framed) }
}

/** Outgoing-only scrub for old, transcript-pinned recall, whose sources cannot be revalidated. */
internal fun List<LLMMessage>.withoutStoredRecallReminders(): List<LLMMessage> {
    var changed = false
    val out = mapNotNull { message ->
        if (message.role != LLMMessage.Role.USER) return@mapNotNull message
        val content = stripStoredRecallReminders(message.content)
        val parts = message.contentParts.mapNotNull { part ->
            if (part !is AgentContentPart.Text) return@mapNotNull part
            val cleaned = stripStoredRecallReminders(part.text)
            if (cleaned == part.text) part else if (cleaned.isBlank()) null else AgentContentPart.Text(cleaned)
        }
        if (content == message.content && parts == message.contentParts) return@mapNotNull message
        changed = true
        if (content.isBlank() && parts.isEmpty() && message.imageParts.isEmpty() && message.audioParts.isEmpty()) null
        else message.copy(content = content.trimEnd(), contentParts = parts)
    }
    return if (changed) out else this
}

internal fun stripStoredRecallReminders(text: String): String =
    if (RECALL_NOTE_MARKER in text) storedRecallFrame.replace(text, "") else text

private val storedRecallFrame = Regex(
    "(?s)(?:\\A|\\r?\\n)<system-reminder>\\s*(?:\\[MEMORY_REFERENCE[^\\r\\n]*\\]\\s*)?" +
        Regex.escape(RECALL_NOTE_MARKER) + "\\r?\\n.*?\\r?\\n</system-reminder>(?=\\s|\\z)",
)

/** Only call after a successful provider attempt has passed its truncation/retry decisions. */
internal suspend fun ChatViewModel.recordSuccessfulMemoryDelivery(
    daily: DailyMemoryDelivery?,
    actualSystemPrompt: String?,
    actualRequestHistory: List<LLMMessage>,
    recall: RecallReminder?,
    realUserMessageId: String?,
) {
    if (realUserMessageId.isNullOrBlank() || (daily?.spans.isNullOrEmpty() && recall == null)) return
    val deliveredAt = System.currentTimeMillis()
    val filesDir = context.filesDir
    withContext(Dispatchers.IO) {
        val deliveredRecall = persistSuccessfulMemoryDelivery(
            daily, actualSystemPrompt, actualRequestHistory, recall, realUserMessageId, deliveredAt, filesDir,
        ) ?: return@withContext
        if (deliveredRecall.sessionId == noticesSessionId) {
            announcedRecallKeys.addAll(deliveredRecall.hits.map { it.announcementKey })
            NoticeLedgerStore.save(context.filesDir, deliveredRecall.sessionId, currentNoticeState())
        }
    }
}

/** Request payload is the proof of delivery; lifecycle validates original entry identity again on write. */
internal fun persistSuccessfulMemoryDelivery(
    daily: DailyMemoryDelivery?,
    actualSystemPrompt: String?,
    actualRequestHistory: List<LLMMessage>,
    recall: RecallReminder?,
    realUserMessageId: String?,
    deliveredAt: Long,
    filesDir: java.io.File? = null,
): RecallReminder? {
    val userId = realUserMessageId?.takeIf { it.isNotBlank() } ?: return null
    val session = (daily?.sessionId ?: recall?.sessionId)?.takeIf { it.isNotBlank() } ?: return null
    val turn = "$session\u0000$userId"
    val refs = daily?.deliveredIn(actualSystemPrompt).orEmpty()
    val deliveredRecall = recall?.takeIf {
        it.sessionId == session && it.turnId == userId && it.deliveredIn(actualRequestHistory)
    }
    val recallRefs = deliveredRecall?.hits?.mapNotNullTo(HashSet()) { it.ref }.orEmpty()
    if (daily?.projectedAt != null && daily.projectedAt == deliveredRecall?.projectedAt) {
        MemoryLifecycle.recordDelivery(refs + recallRefs, turn, deliveredAt, selectedAt = daily.projectedAt)
    } else {
        if (daily?.projectedAt != null) MemoryLifecycle.recordDelivery(refs, turn, deliveredAt, selectedAt = daily.projectedAt)
        if (deliveredRecall != null) {
            MemoryLifecycle.recordDelivery(recallRefs, turn, deliveredAt, selectedAt = deliveredRecall.projectedAt)
        }
    }
    if (filesDir != null && deliveredRecall != null) {
        val usageDir = memoryUsageSessionDirectory(filesDir, session)
        if (usageDir != null) {
            val shared = runCatching { MemoryIndex.sharedDir(filesDir).canonicalFile }.getOrNull()
            val lines = deliveredRecall.hits.mapNotNullTo(HashSet()) { hit ->
                val directory = hit.directory ?: return@mapNotNullTo null
                MemoryUsageLedger.lineKey(directory == shared, hit.source, hit.line)
            }
            MemoryUsageLedger.recordVerified(usageDir, turn, lines, deliveredAt)
        }
    }
    return deliveredRecall
}

/** The consuming chat owns the counters, even when recall reads from shared memory. */
internal fun memoryUsageSessionDirectory(filesDir: java.io.File, session: String): java.io.File? {
    if (!SessionWorkspace.isSafeId(session) || ':' in session) return null
    return SessionWorkspace.base(filesDir, session)
}
