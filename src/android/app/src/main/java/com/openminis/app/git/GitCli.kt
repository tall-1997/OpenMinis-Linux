package com.openminis.app.git

import com.openminis.app.sandbox.ExecutionCoordinator

/**
 * Session-bound Git operations for the Git panel (desktop-code parity).
 *
 * Every command runs through [ExecutionCoordinator] in the session's
 * persistent PRoot shell, so it shares the agent's cwd (the session or
 * project workspace), its mutex (no interleaved half-commands) and its
 * budget classifier. Nothing here is destructive by design: the panel
 * can stage, unstage and commit, but never reset --hard / clean / push.
 *
 * Parsers are pure and unit-tested (GitCliParseTest); the shell layer is
 * a thin wrapper that only quotes paths and picks machine-readable git
 * flags (`--porcelain`, `%x1f` field separators, `-c core.quotepath=false`
 * so unicode paths arrive unescaped).
 */
object GitCli {

    /** Probe whether the session cwd is inside a work tree, plus identity. */
    const val PROBE_CMD =
        "git rev-parse --is-inside-work-tree 2>/dev/null && " +
            "git rev-parse --show-toplevel && " +
            "git branch --show-current; " +
            "printf 'cfg\\t%s\\t%s\\n' \"\$(git config user.name 2>/dev/null)\" " +
            "\"\$(git config user.email 2>/dev/null)\""

    fun statusCmd() = "git -c core.quotepath=false status --porcelain -b"

    fun logCmd(limit: Int) =
        "git -c core.quotepath=false log -n $limit --date=unix " +
            "--pretty=format:%H%x1f%h%x1f%an%x1f%ad%x1f%s"

    /** Diff cap keeps a huge `git show` from blowing the Compose text node. */
    private const val DIFF_MAX_CHARS = 200_000

    // ---------------------------------------------------------------- models

    data class GitRepoInfo(
        val isRepo: Boolean,
        val root: String = "",
        val branch: String = "",
        val userName: String? = null,
        val userEmail: String? = null,
        val error: String? = null,
    )

    data class GitFileEntry(
        val path: String,
        /** Rename source (`R old -> new`); equals [path] when not a rename. */
        val origPath: String = path,
        /** Porcelain XY status char: M/A/D/R/C/T/U/? */
        val code: Char,
        val staged: Boolean,
        val untracked: Boolean = false,
    )

    data class GitStatus(
        val ahead: Int = 0,
        val behind: Int = 0,
        val upstream: String? = null,
        val entries: List<GitFileEntry> = emptyList(),
        val error: String? = null,
    ) {
        val clean: Boolean get() = entries.isEmpty() && error == null
        val stagedCount: Int get() = entries.count { it.staged }
    }

    data class GitCommit(
        val hash: String,
        val shortHash: String,
        val author: String,
        val epochSeconds: Long,
        val subject: String,
    )

    /** Result of a `git commit`: ok flag + human text (git's own output or error). */
    data class CommitOutcome(val ok: Boolean, val message: String)

    // --------------------------------------------------------------- parsers

    fun parseProbe(output: String, exitCode: Int): GitRepoInfo {
        val lines = output.lines()
        val inside = lines.firstOrNull()?.trim()
        if (inside != "true") {
            return GitRepoInfo(
                isRepo = false,
                error = if (output.isBlank()) "exit=$exitCode" else output.trim().take(500),
            )
        }
        val root = lines.getOrNull(1)?.trim().orEmpty()
        val branch = lines.getOrNull(2)?.trim().orEmpty()
        val cfg = lines.firstOrNull { it.startsWith("cfg\t") }
        val parts = cfg?.split('\t')
        val name = parts?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val email = parts?.getOrNull(2)?.takeIf { it.isNotBlank() }
        return GitRepoInfo(
            isRepo = true,
            root = root,
            branch = branch.ifEmpty { "HEAD" },
            userName = name,
            userEmail = email,
        )
    }

    fun parseStatus(output: String): GitStatus {
        var ahead = 0
        var behind = 0
        var upstream: String? = null
        val entries = mutableListOf<GitFileEntry>()
        for (raw in output.lines()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("## ")) {
                val body = line.removePrefix("## ")
                // "## main...origin/main [ahead 1, behind 2]" | "## main" |
                // "## No commits yet on main" | "## HEAD (no branch)"
                val bracket = body.indexOf(" [")
                val head = if (bracket >= 0) body.substring(0, bracket) else body
                if (head.contains("...")) {
                    upstream = head.substringAfter("...").ifEmpty { null }
                }
                if (bracket >= 0) {
                    val meta = body.substring(bracket + 2).removeSuffix("]")
                    ahead = Regex("ahead (\\d+)").find(meta)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    behind = Regex("behind (\\d+)").find(meta)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                continue
            }
            if (line.length < 4) continue
            val x = line[0]
            val y = line[1]
            var rest = line.substring(3)
            var origPath = rest
            val arrow = rest.indexOf(" -> ")
            if (arrow >= 0) {
                origPath = rest.substring(0, arrow)
                rest = rest.substring(arrow + 4)
            }
            if (x == '?' && y == '?') {
                entries += GitFileEntry(rest, rest, '?', staged = false, untracked = true)
                continue
            }
            if (x != ' ' && x != '?') {
                entries += GitFileEntry(rest, origPath, x, staged = true)
            }
            if (y != ' ' && y != '?') {
                entries += GitFileEntry(rest, origPath, y, staged = false)
            }
        }
        return GitStatus(ahead, behind, upstream, entries)
    }

    fun parseLog(output: String): List<GitCommit> = output.lines().mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank()) return@mapNotNull null
        val f = line.split('\u001f')
        if (f.size < 5) return@mapNotNull null
        GitCommit(
            hash = f[0],
            shortHash = f[1],
            author = f[2],
            epochSeconds = f[3].toLongOrNull() ?: 0L,
            subject = f.drop(4).joinToString("\u001f"),
        )
    }

    /**
     * Commit via a base64-relayed message file: the message never touches
     * shell quoting (b64 alphabet is quote-free), and `-F` preserves
     * multi-line bodies verbatim.
     */
    fun buildCommitCommand(messageB64: String): String =
        "printf '%s' '$messageB64' | base64 -d > /tmp/.minis-git-msg && " +
            "git commit -F /tmp/.minis-git-msg && rm -f /tmp/.minis-git-msg"

    /** POSIX single-quote escaping: `'` → `'\''`. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun truncateDiff(s: String): String =
        if (s.length <= DIFF_MAX_CHARS) s
        else s.take(DIFF_MAX_CHARS) + "\n… [diff truncated at ${DIFF_MAX_CHARS / 1000}K chars]"

    // -------------------------------------------------------------- commands

    private suspend fun run(sessionId: String, cmd: String): ExecutionCoordinator.CommandResult =
        ExecutionCoordinator.execute(sessionId, cmd)

    suspend fun probe(sessionId: String): GitRepoInfo {
        val r = runCatching { run(sessionId, PROBE_CMD) }.getOrElse {
            return GitRepoInfo(isRepo = false, error = it.message ?: "shell unavailable")
        }
        return parseProbe(r.output, r.exitCode)
    }

    suspend fun status(sessionId: String): GitStatus {
        val r = runCatching { run(sessionId, statusCmd()) }.getOrElse {
            return GitStatus(error = it.message ?: "shell unavailable")
        }
        if (r.exitCode != 0) return GitStatus(error = r.output.take(500))
        return parseStatus(r.output)
    }

    suspend fun log(sessionId: String, limit: Int = 50): List<GitCommit> {
        val r = runCatching { run(sessionId, logCmd(limit)) }.getOrElse { return emptyList() }
        // exit 128 = no commits yet; that is an empty history, not an error.
        return if (r.exitCode == 0) parseLog(r.output) else emptyList()
    }

    /** Working-tree diff for one entry; untracked files diff against /dev/null. */
    suspend fun fileDiff(sessionId: String, entry: GitFileEntry): String {
        val path = shellQuote(entry.path)
        val cmd = when {
            entry.untracked ->
                "git -c core.quotepath=false diff --no-index -- /dev/null $path"
            entry.staged ->
                "git -c core.quotepath=false diff --cached -- $path"
            else ->
                "git -c core.quotepath=false diff -- $path"
        }
        val r = runCatching { run(sessionId, cmd) }.getOrElse {
            return "[diff unavailable: ${it.message}]"
        }
        // --no-index exits 1 when files differ — that is the success path.
        if (r.exitCode != 0 && !entry.untracked) {
            return "[git diff exit=${r.exitCode}]\n${r.output.take(2000)}"
        }
        return truncateDiff(r.output.ifBlank { "[empty diff]" })
    }

    suspend fun stagedDiff(sessionId: String): String {
        val r = runCatching {
            run(sessionId, "git -c core.quotepath=false diff --cached")
        }.getOrElse { return "" }
        return if (r.exitCode == 0) r.output else ""
    }

    suspend fun commitDiff(sessionId: String, hash: String): String {
        val quoted = shellQuote(hash)
        val r = runCatching {
            run(sessionId, "git -c core.quotepath=false show --date=iso $quoted")
        }.getOrElse { return "[diff unavailable: ${it.message}]" }
        if (r.exitCode != 0) return "[git show exit=${r.exitCode}]\n${r.output.take(2000)}"
        return truncateDiff(r.output)
    }

    /** @return error text, or null on success. */
    suspend fun setStaged(sessionId: String, entry: GitFileEntry, staged: Boolean): String? {
        val path = shellQuote(entry.path)
        val cmd = if (staged) "git add -- $path" else "git restore --staged -- $path"
        val r = runCatching { run(sessionId, cmd) }.getOrElse {
            return it.message ?: "shell unavailable"
        }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    suspend fun stageAll(sessionId: String): String? {
        val r = runCatching { run(sessionId, "git add -A") }.getOrElse {
            return it.message ?: "shell unavailable"
        }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    /** @return [CommitOutcome]; failure text comes straight from git. */
    suspend fun commit(sessionId: String, message: String): CommitOutcome {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return CommitOutcome(false, "empty commit message")
        val b64 = android.util.Base64.encodeToString(
            trimmed.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val r = runCatching { run(sessionId, buildCommitCommand(b64)) }.getOrElse {
            return CommitOutcome(false, it.message ?: "shell unavailable")
        }
        return if (r.exitCode == 0) CommitOutcome(true, r.output.trim())
        else CommitOutcome(false, r.output.trim().ifBlank { "git commit exit=${r.exitCode}" })
    }
}
