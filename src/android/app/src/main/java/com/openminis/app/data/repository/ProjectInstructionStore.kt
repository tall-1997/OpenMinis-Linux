package com.openminis.app.data.repository

import com.openminis.app.data.authority.InstructionUse
import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.data.authority.MemoryOrigin
import com.openminis.app.data.authority.MemoryTrust
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File

/**
 * [T-memory-project-rules] Project-scoped standing rules — the Android analogue
 * of codex's `AGENTS.md` discovery (`codex-rs/core/src/agents_md.rs`).
 *
 * codex walks from the repository root down to the working directory collecting
 * one `AGENTS.md` per level, with `AGENTS.override.md` taking precedence in the
 * same directory, and never walks past the project root. OpenMinis has no
 * directory hierarchy inside a sandbox (the agent's cwd is always the sandbox
 * root), so the meaningful levels are the ones the app itself models:
 *
 *   app-wide GLOBAL.md  →  project rules  →  session GLOBAL.md
 *
 * The later file wins on conflict, which is why the prompt injects them in that
 * order and says so in each header. Project rules are shared by every chat filed
 * in the same project folder, so a rule that only applies to one chat belongs in
 * the session file.
 *
 * The file lives in the project workspace (`minis-workspaces/<folderId>/`),
 * which the sandbox mounts, so the agent can create and edit it like any other
 * workspace file.
 */
object ProjectInstructionStore {

    /** Same name codex uses, so a repository carrying AGENTS.md keeps working. */
    const val PROJECT_RULES_FILE = "AGENTS.md"

    /** Per-directory override, mirroring codex's `AGENTS.override.md`. */
    const val PROJECT_OVERRIDE_FILE = "AGENTS.override.md"

    /** Cap on what is injected; a runaway file must not crowd out the prompt. */
    internal const val MAX_CHARS = 16_000

    data class Resolved(
        val file: File,
        val content: String,
        /** File name actually used — surfaced in the header so the model knows the source. */
        val name: String,
    )

    // Fingerprint memo: prompt assembly runs per turn, and re-reading a file that
    // did not change is pure waste. Key = path + mtime + length.
    //
    // [T-project-rules-cache-atomic] ONE atomic slot instead of three
    // volatiles: the old triple could be read between writes during a
    // concurrent rebuild (two ChatViewModels, context sheet) and serve a
    // MIX of one project's key with another's value — the "rules silently
    // vanished / swapped" reports.
    private class CacheSlot(val key: String, val path: String, val value: Resolved?)

    @Volatile private var cache: CacheSlot? = null

    /** Resolve the project rules for [sessionId], or null when the session is unfiled / has none. */
    fun resolve(
        filesDir: File,
        sessionId: String,
        read: (File) -> String = { it.readText() },
    ): Resolved? {
        val folderId = SessionWorkspace.folderIdFor(sessionId) ?: return forget()
        if (!SessionWorkspace.isSafeId(folderId)) return forget()
        val root = SessionWorkspace.projectBase(filesDir, folderId)
        val override = File(root, PROJECT_OVERRIDE_FILE)
        val base = File(root, PROJECT_RULES_FILE)
        // An empty override must not shadow a populated AGENTS.md.
        val chosen = when {
            override.isFile && override.length() > 0L -> override
            base.isFile && base.length() > 0L -> base
            else -> return forget()
        }
        val key = "${chosen.absolutePath}:${chosen.lastModified()}:${chosen.length()}"
        cache?.takeIf { it.key == key }?.let { return it.value }

        val text = runCatching { read(chosen) }
        if (text.isFailure) {
            // [T-memory-project-rules-lastgood] An unobservable candidate keeps the
            // last good render, mirroring DSH's `agent-instructions`. Dropping the
            // rules because one read failed would silently change what the model is
            // told to do; a stale render is the safer failure. Same file only — a
            // previously resolved project's rules must never stand in for this one.
            return cache?.takeIf { it.path == chosen.absolutePath }?.value
        }
        val resolved = text.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { Resolved(chosen, it.take(MAX_CHARS), chosen.name) }
        cache = CacheSlot(key, chosen.absolutePath, resolved)
        return resolved
    }

    /**
     * The prompt fragment: header (precedence + source) wrapped in the standard
     * memory-authority envelope. It is a standing instruction — the user put the
     * file there deliberately — but the current user request still wins.
     */
    fun fragment(filesDir: File, sessionId: String): String? {
        val resolved = resolve(filesDir, sessionId) ?: return null
        val header = "Project standing rules (${resolved.name} — shared by every chat in this " +
            "project). The session-scoped rules below win where they conflict; the app-wide " +
            "rules still apply wherever this file is silent. If the user's latest message " +
            "conflicts with any of them, defer to the user's latest message:\n"
        return MemoryAuthority.withEnvelope(
            header + resolved.content,
            MemoryTrust.USER_SUBMITTED,
            MemoryOrigin.INTERFACE,
            use = InstructionUse.STANDING_INSTRUCTION,
        )
    }

    /** Test seam: drop the fingerprint memo. */
    internal fun clearCacheForTest() {
        cache = null
    }

    /** Legitimate absence (unfiled session, deleted file) clears the memo. */
    private fun forget(): Resolved? {
        cache = null
        return null
    }
}
