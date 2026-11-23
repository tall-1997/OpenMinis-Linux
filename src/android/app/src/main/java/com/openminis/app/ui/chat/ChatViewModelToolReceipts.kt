package com.openminis.app.ui.chat

import com.openminis.app.data.display.DisplayParts
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ReceiptHistoryRow
import com.openminis.app.data.repository.receiptHistoryContext
import com.openminis.app.data.repository.receiptPartsJson
import com.openminis.app.service.ActiveRunContext
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.ToolErrorCode
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolReceiptDispatch
import com.openminis.app.tools.ToolReceiptPair
import com.openminis.app.tools.ToolReceiptScope
import com.openminis.app.tools.ToolReceiptStore
import com.openminis.app.tools.WritePathGuard
import com.openminis.app.tools.isReceiptAdvisory
import com.openminis.app.tools.isReceiptHash
import com.openminis.app.tools.needsToolReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal fun ChatViewModel.toolReceiptStore() = ToolReceiptStore(
    File(context.noBackupFilesDir, "tool-receipts-v1"),
    syncDirectory = { dir ->
        try {
            val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        } catch (e: android.system.ErrnoException) {
            throw java.io.IOException("Could not sync receipt directory", e)
        }
    },
)

internal suspend fun ChatViewModel.reconcilePersistedToolTurn(
    sessionId: String,
    userTurnId: String?,
    assistantDbId: String?,
    toolResultDbId: String?,
    assistantParts: List<AgentContentPart>,
    resultParts: List<AgentContentPart>,
) = withContext(Dispatchers.IO) {
    val scope = ToolReceiptScope.userTurn(sessionId, userTurnId) ?: return@withContext
    val pairs = persistedToolReceiptPairs(scope, assistantDbId, toolResultDbId, assistantParts, resultParts)
    toolReceiptStore().reconcile(scope.sessionSha, pairs)
    Unit
}

/** For callers that already have the original persisted rows mapped, not repaired provider/UI history. */
internal suspend fun ChatViewModel.reconcileLoadedToolReceipts(sessionId: String, persistedHistory: List<LLMMessage>) =
    withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext
        val store = toolReceiptStore()
        val sessionSha = ToolReceiptScope.sessionDigest(sessionId)
        if (store.pending(sessionSha)?.hasUnpaired == true) {
            store.reconcile(sessionSha, loadedToolReceiptPairs(sessionId, persistedHistory))
        }
        Unit
    }

/**
 * Reconcile only when this session actually needs it. A page may start after its user anchor or
 * end just before its result row; look up only bounded adjacent context from the same session.
 * Never parse view history, hydrate media, or treat a display fallback as a persisted result.
 */
internal suspend fun ChatViewModel.reconcileReceiptRows(
    sessionId: String,
    rows: List<ReceiptHistoryRow>,
    includePageContext: Boolean = true,
) = withContext(Dispatchers.IO) {
    if (sessionId.isBlank() || rows.isEmpty()) return@withContext
    val store = toolReceiptStore()
    val sessionSha = ToolReceiptScope.sessionDigest(sessionId)
    if (store.pending(sessionSha)?.hasUnpaired != true) return@withContext
    val page = rows.takeLast(MAX_RECEIPT_PAGE_ROWS)
    if (page.any { it.sessionId != sessionId }) return@withContext
    val contextRows = if (includePageContext) chatRepository.receiptHistoryContext(sessionId, page, MAX_RECEIPT_LOOKBACK)
    else page
    val scanner = ReceiptTranscriptScanner(sessionId)
    var previousSort: Int? = null
    var remainingChars = MAX_RECEIPT_SCAN_CHARS
    for (row in contextRows) {
        currentCoroutineContext().ensureActive()
        val sort = previousSort
        if (row.sessionId != sessionId || (sort != null && row.sortOrder <= sort)) return@withContext
        if (sort != null && row.sortOrder.toLong() - sort > 1) scanner.consume(null)
        previousSort = row.sortOrder
        val size = row.projectedSize
        if (size > MAX_RECEIPT_ROW_CHARS || size > remainingChars) {
            scanner.consume(null)
            continue
        }
        val json = chatRepository.receiptPartsJson(row)
        if (json == null || json.length > remainingChars || json.length > MAX_RECEIPT_ROW_CHARS) {
            scanner.consume(null)
            continue
        }
        remainingChars -= json.length
        scanner.consume(receiptTranscriptRow(row.id, row.role, json))
    }
    store.reconcile(sessionSha, scanner.pairs)
    Unit
}

/** Aggregate diagnostics for the loaded session, never model-facing or a reconciliation input. */
internal suspend fun ChatViewModel.logLegacyToolReceiptDiagnostics(sessionId: String) {
    val diagnostics = legacyToolReceiptDiagnostics(sessionId) ?: return
    if (activeSessionId == sessionId && diagnostics.unsettled > 0) {
        android.util.Log.w(ChatViewModel.TAG, "Legacy tool receipt records without a settled result: ${diagnostics.unsettled}")
    }
}



private const val MAX_RECEIPT_PAGE_ROWS = 512
private const val MAX_RECEIPT_LOOKBACK = 64
private const val MAX_RECEIPT_PARTS = 128
private const val MAX_RECEIPT_ROW_CHARS = 768 * 1024
private const val MAX_RECEIPT_SCAN_CHARS = 4 * 1024 * 1024

/** Decode only identifiers, finalized input and the host proof; no output copy or attachment IO. */
internal fun receiptTranscriptRow(id: String, role: String, partsJson: String): LLMMessage? {
    if (id.isBlank() || partsJson.length > MAX_RECEIPT_ROW_CHARS) return null
    val messageRole = when (role) {
        "assistant" -> LLMMessage.Role.ASSISTANT
        "user" -> LLMMessage.Role.USER
        else -> return null
    }
    return try {
        val parts = JSONArray(partsJson)
        if (parts.length() > MAX_RECEIPT_PARTS || DisplayParts.isStub(partsJson)) return null
        val transcript = mutableListOf<AgentContentPart>()
        var firstUserText = ""
        var isHostGenerated = false
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            when (part.optString("type")) {
                "text" -> if (messageRole == LLMMessage.Role.USER && firstUserText.isBlank()) {
                    firstUserText = (part.opt("value") as? String).orEmpty()
                }
                "receiptOrigin" -> if (part.opt("value") == "host") isHostGenerated = true
                "mediaRef" -> if (messageRole == LLMMessage.Role.USER && part.optJSONObject("value") != null) {
                    // Just an attachment-presence bit. Never open its file or synthesize a row.
                    transcript += AgentContentPart.Text("[attachment]")
                }
                "toolUse" -> {
                    val v = part.getJSONObject("value")
                    transcript += AgentContentPart.ToolUse.fromInputJson(
                        v.getString("toolUseId"), v.getString("name"), v.getString("input"),
                    )
                }
                "toolResult" -> {
                    val v = part.getJSONObject("value")
                    transcript += AgentContentPart.ToolResult(
                        v.getString("toolUseId"), v.optString("name"), "", v.opt("success") != true,
                        receiptProof = (v.opt("receiptProof") as? String)?.takeIf { it.isReceiptHash() },
                    )
                }
            }
        }
        LLMMessage(messageRole, firstUserText, contentParts = transcript, dbMessageId = id, isHostGenerated = isHostGenerated)
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }
}

internal fun loadedToolReceiptPairs(sessionId: String, history: List<LLMMessage>): List<ToolReceiptPair> =
    ReceiptTranscriptScanner(sessionId).apply { history.takeLast(MAX_RECEIPT_PAGE_ROWS).forEach(::consume) }.pairs

/** Only adjacent original rows after a known real user row can make a pair. */
internal class ReceiptTranscriptScanner(private val sessionId: String) {
    private var scope: ToolReceiptScope? = null
    private var assistant: LLMMessage? = null
    val pairs = mutableListOf<ToolReceiptPair>()

    fun consume(row: LLMMessage?) {
        if (row == null || row.dbMessageId.isNullOrBlank()) {
            scope = null
            assistant = null
            return
        }
        if (row.role == LLMMessage.Role.ASSISTANT) {
            assistant = row
            return
        }
        if (row.contentParts.any { it is AgentContentPart.ToolResult }) {
            val preceding = assistant
            val anchor = scope
            if (preceding != null && anchor != null && pairs.size < MAX_RECEIPT_PAGE_ROWS) {
                val batch = persistedToolReceiptPairs(anchor, preceding.dbMessageId, row.dbMessageId,
                    preceding.contentParts, row.contentParts)
                pairs.addAll(batch.take(MAX_RECEIPT_PAGE_ROWS - pairs.size))
            }
        } else if (receiptUserRow(row)) {
            scope = ToolReceiptScope.userTurn(sessionId, row.dbMessageId)
        }
        assistant = null
    }
}

internal fun receiptUserRow(row: LLMMessage): Boolean =
    row.role == LLMMessage.Role.USER && !row.dbMessageId.isNullOrBlank() && !row.isHostGenerated &&
        row.contentParts.none { it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse } &&
        !HarnessNoteMarkers.isUserHarnessNote(row) && !legacyReceiptHostNote(row.content) &&
        (row.content.isNotBlank() || row.contentParts.any { it !is AgentContentPart.Text || it.text.isNotBlank() } ||
            row.imageParts.isNotEmpty() || row.audioParts.isNotEmpty())

private fun legacyReceiptHostNote(content: String): Boolean {
    val text = content.trimStart()
    return text.startsWith("<system-reminder>" + com.openminis.app.agent.InterruptedTailDetector.CONTINUE_REMINDER_MARKER) ||
        text.startsWith("<system-reminder>" + com.openminis.app.agent.StreamDropReminder.MARKER)
}

/** Use only the exact request projection, never an attempt counter or a live UI bubble. */
internal fun receiptAfterResultRowId(history: List<LLMMessage>, userTurnId: String?): String? {
    if (userTurnId.isNullOrBlank()) return null
    var candidate: String? = null
    for (index in history.indices.reversed()) {
        val row = history[index]
        if (receiptUserRow(row)) return candidate?.takeIf { row.dbMessageId == userTurnId }
        if (candidate != null || row.role != LLMMessage.Role.USER || row.isHostGenerated || row.dbMessageId.isNullOrBlank()) continue
        val owner = history.getOrNull(index - 1) ?: continue
        if (owner.role != LLMMessage.Role.ASSISTANT || owner.dbMessageId.isNullOrBlank() || owner.isHostGenerated ||
            owner.dbMessageId == row.dbMessageId || owner.contentParts.size > MAX_RECEIPT_PARTS ||
            row.contentParts.size > MAX_RECEIPT_PARTS
        ) continue
        val uses = owner.contentParts.filterIsInstance<AgentContentPart.ToolUse>().groupBy { it.id }
        val results = row.contentParts.filterIsInstance<AgentContentPart.ToolResult>().groupBy { it.id }
        if (results.any { (id, parts) ->
                val result = parts.singleOrNull()
                val use = uses[id]?.singleOrNull()
                id.isNotBlank() && result?.receiptProof?.isReceiptHash() == true && use != null &&
                    receiptDispatchName(result.name) == receiptDispatchName(use.name)
            }
        ) candidate = row.dbMessageId
    }
    return null // A compacted view can keep a result while dropping its real user anchor.
}

internal fun persistedToolReceiptPairs(
    scope: ToolReceiptScope,
    assistantRowId: String?,
    resultRowId: String?,
    assistantParts: List<AgentContentPart>,
    resultParts: List<AgentContentPart>,
): List<ToolReceiptPair> {
    if (assistantRowId.isNullOrBlank() || resultRowId.isNullOrBlank() || assistantRowId == resultRowId ||
        assistantParts.size > MAX_RECEIPT_PARTS || resultParts.size > MAX_RECEIPT_PARTS
    ) return emptyList()
    val uses = assistantParts.filterIsInstance<AgentContentPart.ToolUse>().groupBy { it.id }
    val results = resultParts.filterIsInstance<AgentContentPart.ToolResult>().groupBy { it.id }
    return uses.mapNotNull { (id, sameIdUses) ->
        val use = sameIdUses.singleOrNull() ?: return@mapNotNull null
        val result = results[id]?.singleOrNull() ?: return@mapNotNull null
        val proof = result.receiptProof?.takeIf { it.isReceiptHash() } ?: return@mapNotNull null
        val canonical = receiptDispatchName(use.name)
        if (id.isBlank() || canonical != receiptDispatchName(result.name)) return@mapNotNull null
        val args = use.inputJson
        if (!needsToolReceipt(canonical, args)) return@mapNotNull null
        val key = scope.call(canonical, args) ?: return@mapNotNull null
        ToolReceiptPair(key, id, assistantRowId, resultRowId, proof)
    }
}

/**
 * A pending-delivery advisory rides AFTER the sandbox's real output — it must
 * never replace the script's own stdout/return value, which may hold completed
 * work the model still needs.
 */
internal fun scriptSettledResult(script: ToolExecutionResult, advisory: ToolExecutionResult): ToolExecutionResult {
    val output = when {
        advisory.output.isBlank() -> script.output
        script.output.isBlank() -> advisory.output
        else -> "${script.output}\n\n${advisory.output}"
    }
    return script.copy(output = output)
}

/** The blocking JS callbacks must inherit cancellation and lane guards, not create fresh authority. */
internal suspend fun ChatViewModel.dispatchScriptWithReceipts(
    parent: ToolReceiptDispatch.Delivery?,
    outerId: String,
    nestedRefusal: ((String, String) -> ToolExecutionResult?)?,
    readOnlyMemory: Boolean,
    execute: suspend (suspend (String, String) -> ToolExecutionResult) -> ToolExecutionResult,
): ToolExecutionResult {
    val caller = currentCoroutineContext()
    val inherited = listOfNotNull(caller[Job], caller[SubAgentLane], caller[WritePathGuard.Scope], caller[ActiveRunContext])
        .fold(EmptyCoroutineContext as CoroutineContext) { ctx, element -> ctx + element }
    val scope = parent?.let { delivery -> delivery.key.scope.child("script", "callbacks", delivery.instanceSha) }
    var advisory: ToolExecutionResult? = null
    var nestedCount = 0
    val result = execute { name, args ->
        withContext(inherited) {
            advisory?.let { return@withContext it }
            val canonical = receiptDispatchName(name)
            if (canonical in setOf("execute_code", "execute_code_js")) {
                return@withContext ToolExecutionResult("Nested script execution is not allowed.", false)
            }
            if (readOnlyMemory && canonical == "memory_get" && needsToolReceipt(canonical, args)) {
                return@withContext ToolExecutionResult("The execute_code sandbox cannot restore archived memory.", false,
                    errorCode = ToolErrorCode.PERMISSION_DENIED)
            }
            val ordinal = ++nestedCount
            val callId = "${parent?.instanceSha ?: outerId}-$ordinal"
            executeTool(name, args, callId, receiptScope = scope?.step("callback", ordinal), nestedRefusal = nestedRefusal)
                .also { if (it.isReceiptAdvisory()) advisory = it }
        }
    }
    caller.ensureActive()
    val settled = advisory?.let { note -> scriptSettledResult(result, note) } ?: result
    // The sandbox ran the script to a terminal state (success, executed
    // failure, or a proven refusal) — settle the outer receipt here, aligned
    // with shell failures, BEFORE any fallible output rendering.
    parent?.completed(settled)
    // A nested result's proof does not authorize reconciling the outer call.
    return settled.copy(receiptProof = null)
}
