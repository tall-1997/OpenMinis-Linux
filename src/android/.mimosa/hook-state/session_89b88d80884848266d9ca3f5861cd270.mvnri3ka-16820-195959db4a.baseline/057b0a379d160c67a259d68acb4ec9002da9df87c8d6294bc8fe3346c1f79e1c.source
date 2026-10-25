package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File

/**
 * [T-context-assembly-preview] Captures the assembled system prompt for
 * debugging "what did the model actually receive". Every capture keeps a
 * per-section size breakdown (first line of each paragraph as its label)
 * plus the full text, both in memory (ring of 8) and on disk under the
 * session's `offloads/context-assembly/` (host side — the app process has
 * no `/var/minis` mount; [SessionWorkspace.hostDir] resolves the same
 * directory the shell bind uses) so the agent itself can read it back with
 * shell tools.
 *
 * [T-diagnostics-host-path] The first cut of this file wrote to a literal
 * `/var/minis/...` constant — a PRoot-guest path that does not exist in the
 * app process — so the disk snapshot never materialized and the shared
 * `latest.md` was silently overwritten across sessions. Both are fixed
 * here: host path via [SessionWorkspace.hostDir], one directory per
 * session, and failures are logged instead of swallowed.
 */
object ContextAssemblySnapshot {
    private const val TAG = "ContextAssembly"

    data class Snapshot(
        val capturedAtMs: Long,
        val sessionId: String,
        val totalChars: Int,
        val sections: List<Pair<String, Int>>,
        val fullText: String,
    )

    @Volatile
    var latest: Snapshot? = null
        private set

    private const val KEEP = 8
    private val ring = ArrayDeque<Snapshot>()

    fun dir(context: Context, sessionId: String): File =
        File(SessionWorkspace.hostDir(context.filesDir, sessionId, "offloads"), "context-assembly")

    @Synchronized
    fun capture(rawText: String, sessionId: String, context: Context) {
        // [T-context-snapshot-redacted] Mask ONCE at the entry point so every
        // downstream consumer — the section labels (first line of each `#`
        // paragraph, so a credential in a heading would leak through the label
        // too), the in-memory ring that LogManagementScreen renders, and the
        // on-disk `latest.md` the agent itself can `cat` — sees the same
        // redacted text. Masking only the disk copy would leave the UI and the
        // ring as the two remaining copies of the raw prompt.
        //
        // Defence in depth, not the primary control: provider keys live in
        // EncryptedPrefs and travel in request headers, so a normal prompt has
        // nothing to mask. This catches a credential the user pasted into
        // SOUL.md / GLOBAL.md / a memory entry, which then rides into every
        // prompt. See SecretMasking for the pattern set.
        val fullText = com.openminis.app.util.SecretMasking.mask(rawText)
        val sections = fullText
            .split(Regex("(?m)^#"))
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null
                else {
                    val head = trimmed.lineSequence().firstOrNull()
                        ?.take(60).orEmpty().trim().ifBlank { "(section)" }
                    head to trimmed.length
                }
            }
            .sortedByDescending { it.second }
        val snapshot = Snapshot(
            capturedAtMs = System.currentTimeMillis(),
            sessionId = sessionId,
            totalChars = fullText.length,
            sections = sections,
            fullText = fullText,
        )
        latest = snapshot
        ring.addLast(snapshot)
        while (ring.size > KEEP) ring.removeFirst()
        runCatching {
            val dir = dir(context, sessionId)
            dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(snapshot.capturedAtMs))
            val sb = StringBuilder()
            sb.append("# 上下文组装快照 ").append(stamp)
                .append(" | 会话 ").append(sessionId)
                .append(" | 总字符 ").append(fullText.length)
                .append("\n\n## 构成（按大小降序）\n\n")
            sections.forEach { (label, len) ->
                sb.append("- ").append(len).append(" 字符 — ").append(label).append("\n")
            }
            sb.append("\n## 全文\n\n").append(fullText)
            File(dir, "latest.md").writeText(sb.toString())
        }.onFailure {
            AppLogger.error(TAG, "context assembly snapshot write failed: ${it.message}")
        }
    }

    fun recent(): List<Snapshot> = synchronized(this) { ring.toList() }
}
