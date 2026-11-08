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

    /**
     * `git` with an optional `-C <root>`: every panel command carries the
     * resolved repo root so a repo nested below the session cwd (the common
     * `workspace/<clone>` layout) works without cd-ing the shared shell —
     * cd-ing would move the agent's cwd out from under it.
     */
    fun gitPrefix(root: String?): String =
        if (root.isNullOrBlank()) "git" else "git -C ${shellQuote(root)}"

    /** Probe whether a directory is inside a work tree, plus identity. */
    fun probeCmd(root: String? = null): String {
        val g = gitPrefix(root)
        return "$g rev-parse --is-inside-work-tree 2>/dev/null && " +
            "$g rev-parse --show-toplevel && " +
            "$g branch --show-current; " +
            "printf 'cfg\\t%s\\t%s\\n' \"\$(" + g + " config user.name 2>/dev/null)\" " +
            "\"\$(" + g + " config user.email 2>/dev/null)\""
    }

    /**
     * Immediate-child repo discovery for when cwd itself is not a repo:
     * prints `./<name>/.git` lines, sorted, capped. mindepth 2 skips cwd's
     * own (absent) `.git`; maxdepth 2 covers exactly one clone level.
     */
    const val DISCOVER_CMD =
        "find . -maxdepth 2 -mindepth 2 -type d -name .git -print 2>/dev/null | sort | head -8"

    /** `./a/.git` lines → repo names relative to cwd (`a`), sorted as given. */
    fun parseDiscover(output: String): List<String> = output.lines()
        .map { it.trim().removePrefix("./") }
        .filter { it.endsWith("/.git") }
        .map { it.removeSuffix("/.git") }
        .filter { it.isNotBlank() }

    fun statusCmd(root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false status --porcelain -b"

    /** Local + remote refs for the branch switcher, newest commits first. */
    fun branchesCmd(root: String? = null) =
        "${gitPrefix(root)} for-each-ref --sort=-committerdate refs/heads refs/remotes " +
            "--format=%(objectname:short)%x1f%(refname:short)%x1f%(contents:subject) | head -40"

    /** Stash list: `stash@{n}<x1f>subject`, newest first. */
    fun stashCmd(root: String? = null) =
        "${gitPrefix(root)} stash list --format=%gd%x1f%s"

    /** Push/pull both refresh status afterwards; upstream must exist for push. */
    fun pushCmd(root: String? = null) = "${gitPrefix(root)} push"
    fun pullCmd(root: String? = null) = "${gitPrefix(root)} pull --ff-only"

    fun logCmd(limit: Int, root: String? = null, skip: Int = 0) =
        "${gitPrefix(root)} -c core.quotepath=false log -n $limit" +
            (if (skip > 0) " --skip $skip" else "") +
            " --date=unix --pretty=format:%H%x1f%h%x1f%an%x1f%ad%x1f%s"

    /** Per-file +/- line counts for staged (`--cached`) or unstaged changes. */
    fun numstatCmd(staged: Boolean, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false diff --numstat" +
            (if (staged) " --cached" else "")

    fun remoteCmd(root: String? = null) = "${gitPrefix(root)} remote -v"

    /**
     * History page: same record as [logCmd] plus `%P` (parents) so the
     * commit-graph lanes can be laid out; `--skip` pages older commits.
     */
    fun graphLogCmd(offset: Int, limit: Int, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false log -n $limit --skip $offset " +
            "--date=unix --pretty=format:%H%x1f%P%x1f%h%x1f%an%x1f%ad%x1f%s"

    /**
     * Commit-detail sheet: identity + dates + subject + body, no diff.
     * Newlines inside %b stay literal — [parseCommitDetail] reassembles
     * multi-line bodies from the trailing lines.
     */
    fun commitDetailCmd(hash: String, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false show --no-patch --date=unix " +
            "--pretty=format:%H%x1f%an%x1f%ae%x1f%at%x1f%cn%x1f%ce%x1f%ct%x1f%s%x1f%b " +
            shellQuote(hash)

    /** Per-file stats of one commit; `--format=` leaves numstat rows only. */
    fun commitNumstatCmd(hash: String, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false show --numstat --format= " +
            shellQuote(hash)

    /** Diff of a single file inside one commit (details sheet → diff view). */
    fun commitFileDiffCmd(hash: String, path: String, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false show --date=iso " +
            shellQuote(hash) + " -- " + shellQuote(path)

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
        /** Repo name relative to the session cwd when adopted via discovery. */
        val relativeRoot: String = "",
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
        /** Parent hashes in order (`%P`); empty when the caller didn't ask for them. */
        val parents: List<String> = emptyList(),
    )

    /**
     * One branch line for the panel's branch switcher, parsed from
     * `git for-each-ref` output (`hash<x1f>name<x1f>subject`). Remote
     * refs (`origin/…`) carry a slash in [name]; [isCurrent] is only
     * ever true for the local branch actually checked out.
     */
    data class GitBranchRef(
        val name: String,
        val isCurrent: Boolean,
        val lastSubject: String,
    )

    /** One stash entry: `stash@{n}` + subject. */
    data class GitStashEntry(val index: Int, val subject: String)

    /** One `git diff --numstat` row: per-file line counts, or a typed marker. */
    data class NumStat(
        val added: Int = 0,
        val deleted: Int = 0,
        /**
         * `-\t-` rows: binary files and 100%-similarity renames carry no
         * counts. Renames land keyed by the *new* path, matching [GitFileEntry.path].
         */
        val binary: Boolean = false,
    )

    /** Full metadata for the commit-detail sheet (`git show --no-patch`). */
    data class GitCommitDetail(
        val hash: String,
        val author: String,
        val authorEmail: String,
        val authorEpoch: Long,
        val committer: String,
        val committerEmail: String,
        val committerEpoch: Long,
        val subject: String,
        val body: String,
    )

    /** One file touched by a commit (`git show --numstat`). */
    data class CommitFileStat(
        val path: String,
        val added: Int,
        val deleted: Int,
        val binary: Boolean,
        /** Rename source (`{old => new}` rows). */
        val renameFrom: String? = null,
    )

    /** Result of a `git commit`: ok flag + human text (git's own output or error). */
    data class CommitOutcome(val ok: Boolean, val message: String)

    // --------------------------------------------------------------- parsers

    /**
     * Shell-wrapper noise that must never reach the user-facing error text:
     * the persistent-shell watchdog prints `Killed` / `( sleep …; kill … )`
     * job lines onto stderr, and the probe's own `cfg` printf runs even when
     * cwd is not a repo.
     */
    private val NOISE_LINE = Regex(
        "(?i)\\bkilled\\b|/bin/bash: line \\d+|^\\(\\s*sleep|sleep \\d+; kill|^cfg\\t",
    )

    fun parseProbe(output: String, exitCode: Int): GitRepoInfo {
        val lines = output.lines()
        val inside = lines.firstOrNull()?.trim()
        if (inside != "true") {
            val clean = lines.filter { !NOISE_LINE.containsMatchIn(it) }
                .joinToString("\n").trim()
            return GitRepoInfo(
                isRepo = false,
                error = if (clean.isBlank()) "exit=$exitCode" else clean.take(500),
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

    /** `hash<x1f>name<x1f>subject` rows → switcher entries; garbage rows drop. */
    fun parseBranches(output: String, current: String): List<GitBranchRef> =
        output.lines().mapNotNull { raw ->
            val parts = raw.trim().split('\u001f')
            if (parts.size < 2) return@mapNotNull null
            val name = parts[1].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GitBranchRef(
                name = name,
                isCurrent = name == current,
                lastSubject = parts.getOrNull(2).orEmpty(),
            )
        }.distinctBy { it.name }

    /** `stash@{n}<x1f>subject` rows → stash entries; garbage rows drop. */
    fun parseStashes(output: String): List<GitStashEntry> =
        output.lines().mapNotNull { raw ->
            val parts = raw.trim().split('\u001f', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val index = Regex("""stash@\{(\d+)\}""")
                .find(parts[0])?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            GitStashEntry(index, parts[1])
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

    /** Same records as [parseLog] with `%P` parents inserted as field 2. */
    fun parseGraphLog(output: String): List<GitCommit> = output.lines().mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank()) return@mapNotNull null
        val f = line.split('\u001f')
        if (f.size < 6) return@mapNotNull null
        GitCommit(
            hash = f[0],
            shortHash = f[2],
            author = f[3],
            epochSeconds = f[4].toLongOrNull() ?: 0L,
            subject = f.drop(5).joinToString("\u001f"),
            parents = f[1].split(' ').filter { it.isNotBlank() },
        )
    }

    /**
     * `added\tdeleted\tpath` rows, plus typed markers:
     * `-\t-\told => new` (pure rename, keyed by the new path) and
     * `-\t-\tpath` (binary). Both the plain and the collapsed-brace
     * (`prefix/{old => new}`) rename spellings resolve via [splitRename].
     * Returns empty on failure output.
     */
    fun parseNumstat(output: String): Map<String, NumStat> {
        val map = mutableMapOf<String, NumStat>()
        for (raw in output.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 3) continue
            val path = f.drop(2).joinToString("\t")
            if (f[0] == "-" && f[1] == "-") {
                map[splitRename(path)?.second ?: path] = NumStat(binary = true)
                continue
            }
            map[path] = NumStat(
                added = f[0].toIntOrNull() ?: 0,
                deleted = f[1].toIntOrNull() ?: 0,
            )
        }
        return map
    }

    /**
     * Rename rows spell the two paths as `old => new` or collapsed as
     * `prefix/{old => new}`; both resolve to `(oldPath, newPath)`. Returns
     * null when the row is not a rename (no ` => ` marker).
     */
    fun splitRename(path: String): Pair<String, String>? {
        val arrow = path.indexOf(" => ")
        if (arrow < 0) return null
        val lhs = path.substring(0, arrow)
        val rhs = path.substring(arrow + 4)
        if ("{" in lhs && rhs.endsWith("}")) {
            val prefix = lhs.substringBeforeLast('{')
            val oldName = lhs.substringAfterLast('{')
            val newName = rhs.substringBeforeLast('}')
            return prefix + oldName to prefix + newName
        }
        return lhs to rhs
    }

    /**
     * First push URL from `git remote -v`, or null when no remote exists.
     * The push entry wins over the fetch entry when their URLs differ;
     * with equal URLs the first line is returned.
     */
    fun parseRemote(output: String): String? {
        var fallback: String? = null
        for (raw in output.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 2) continue
            val url = f[1].substringBefore(" (").trim()
            if (url.isEmpty()) continue
            if ("(push)" in f[1]) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    /**
     * Record line = 9 `\x1f` fields ending with subject+body; the body's own
     * newlines spill into trailing lines and are reassembled verbatim.
     * Shell noise is skipped before the record and inside the body.
     */
    fun parseCommitDetail(output: String): GitCommitDetail? {
        val lines = output.lines()
        val start = lines.indexOfFirst { l ->
            !NOISE_LINE.containsMatchIn(l) &&
                l.substringBefore('\u001f').length == 40 &&
                l.split('\u001f').size >= 9
        }
        if (start < 0) return null
        val f = lines[start].split('\u001f')
        val bodyParts = mutableListOf<String>()
        f.drop(8).joinToString("\u001f").trim().takeIf { it.isNotEmpty() }?.let { bodyParts += it }
        for (l in lines.drop(start + 1)) {
            val line = l.trimEnd('\r')
            if (NOISE_LINE.containsMatchIn(line)) continue
            if (line.isBlank()) break
            bodyParts += line
        }
        return GitCommitDetail(
            hash = f[0],
            author = f[1],
            authorEmail = f[2],
            authorEpoch = f[3].toLongOrNull() ?: 0L,
            committer = f[4],
            committerEmail = f[5],
            committerEpoch = f[6].toLongOrNull() ?: 0L,
            subject = f[7],
            body = bodyParts.joinToString("\n").trim(),
        )
    }

    /**
     * Per-file stats of one commit. `-\t-` rows are binary files or pure
     * renames; rename rows resolve via [splitRename] into renameFrom/new
     * path (both the `old => new` and `prefix/{old => new}` spellings).
     */
    fun parseCommitNumstat(output: String): List<CommitFileStat> {
        val out = mutableListOf<CommitFileStat>()
        for (raw in output.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 3) continue
            val path = f.drop(2).joinToString("\t")
            if (f[0] == "-" && f[1] == "-") {
                val rename = splitRename(path)
                out += if (rename != null) {
                    CommitFileStat(
                        path = rename.second,
                        added = 0,
                        deleted = 0,
                        binary = true,
                        renameFrom = rename.first,
                    )
                } else {
                    CommitFileStat(path, 0, 0, binary = true)
                }
                continue
            }
            out += CommitFileStat(
                path = path,
                added = f[0].toIntOrNull() ?: 0,
                deleted = f[1].toIntOrNull() ?: 0,
                binary = false,
            )
        }
        return out
    }

    /**
     * Commit via a base64-relayed message file: the message never touches
     * shell quoting (b64 alphabet is quote-free), and `-F` preserves
     * multi-line bodies verbatim.
     *
     * [T-git-commit-temp-collision] The message file is keyed by the shell's
     * own pid (`$$`, expanded inside the single `bash -c` this runs in), not a
     * fixed name. The ExecutionCoordinator mutex is per session, so two
     * sessions committing concurrently used to clobber the same
     * `/tmp/.minis-git-msg` — the second `rm -f` could delete the first's file
     * mid-flight. A per-invocation path makes each commit independent.
     */
    fun buildCommitCommand(messageB64: String, root: String? = null): String {
        val msg = "/tmp/.minis-git-msg.$$"
        return "printf '%s' '$messageB64' | base64 -d > $msg && " +
            "${gitPrefix(root)} commit -F $msg && rm -f $msg"
    }

    /** POSIX single-quote escaping: `'` → `'\''`. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun truncateDiff(s: String): String =
        if (s.length <= DIFF_MAX_CHARS) s
        else s.take(DIFF_MAX_CHARS) + "\n… [diff truncated at ${DIFF_MAX_CHARS / 1000}K chars]"

    // -------------------------------------------------------------- commands

    /**
     * [timeoutMs] > 0 shortens the execution class wall (network ops get a
     * caller bound: push/pull 60s, checkout 30s); 0 rides the class default.
     */
    private suspend fun run(
        sessionId: String,
        cmd: String,
        timeoutMs: Long = 0L,
    ): ExecutionCoordinator.CommandResult =
        ExecutionCoordinator.execute(sessionId, cmd, timeout = timeoutMs)

    suspend fun probe(sessionId: String): GitRepoInfo {
        val r = runCatching { run(sessionId, probeCmd()) }.getOrElse {
            return GitRepoInfo(isRepo = false, error = it.message ?: "shell unavailable")
        }
        val direct = parseProbe(r.output, r.exitCode)
        if (direct.isRepo) return direct
        // cwd itself is not a repo. The common layout is a clone *inside* the
        // session workspace (workspace/<name>/.git) — git only searches up, so
        // adopt the first immediate-child repo instead of declaring defeat.
        val d = runCatching { run(sessionId, DISCOVER_CMD) }.getOrNull() ?: return direct
        val rel = parseDiscover(d.output).firstOrNull() ?: return direct
        val r2 = runCatching { run(sessionId, probeCmd(rel)) }.getOrElse { return direct }
        val nested = parseProbe(r2.output, r2.exitCode)
        return if (nested.isRepo) nested.copy(relativeRoot = rel) else direct
    }

    suspend fun status(sessionId: String, root: String? = null): GitStatus {
        val r = runCatching { run(sessionId, statusCmd(root)) }.getOrElse {
            return GitStatus(error = it.message ?: "shell unavailable")
        }
        if (r.exitCode != 0) return GitStatus(error = r.output.take(500))
        return parseStatus(r.output)
    }

    suspend fun log(sessionId: String, limit: Int = 50, root: String? = null): List<GitCommit> {
        val r = runCatching { run(sessionId, logCmd(limit, root)) }.getOrElse { return emptyList() }
        // exit 128 = no commits yet; that is an empty history, not an error.
        return if (r.exitCode == 0) parseLog(r.output) else emptyList()
    }

    /** History page (parents included), paged: [offset] skips, [limit] caps. */
    suspend fun logPage(
        sessionId: String,
        offset: Int,
        limit: Int = 50,
        root: String? = null,
    ): List<GitCommit> {
        val r = runCatching { run(sessionId, graphLogCmd(offset, limit, root)) }
            .getOrElse { return emptyList() }
        return if (r.exitCode == 0) parseGraphLog(r.output) else emptyList()
    }

    suspend fun numstat(
        sessionId: String,
        staged: Boolean,
        root: String? = null,
    ): Map<String, NumStat> {
        val r = runCatching { run(sessionId, numstatCmd(staged, root)) }
            .getOrElse { return emptyMap() }
        return if (r.exitCode == 0) parseNumstat(r.output) else emptyMap()
    }

    /** Branches for the switcher; empty (with no error) outside a repo. */
    suspend fun branches(sessionId: String, root: String?): List<GitBranchRef> {
        val r = runCatching { run(sessionId, branchesCmd(root)) }.getOrElse { return emptyList() }
        if (r.exitCode != 0) return emptyList()
        val current = currentBranch(sessionId, root)
        return parseBranches(r.output, current)
    }

    private suspend fun currentBranch(sessionId: String, root: String?): String =
        runCatching {
            run(sessionId, "${gitPrefix(root)} branch --show-current").output.trim()
        }.getOrDefault("")

    /** Stash list for the Changes tab; empty (with no error) outside a repo. */
    suspend fun stashes(sessionId: String, root: String?): List<GitStashEntry> {
        val r = runCatching { run(sessionId, stashCmd(root)) }.getOrElse { return emptyList() }
        if (r.exitCode != 0) return emptyList()
        return parseStashes(r.output)
    }

    /** @return error text, or null when the push succeeded. */
    suspend fun push(sessionId: String, root: String?): String? {
        val r = runCatching { run(sessionId, pushCmd(root), timeoutMs = 60_000L) }
            .getOrElse { return it.message ?: "shell unavailable" }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    /** @return error text, or null when the ff-only pull succeeded. */
    suspend fun pull(sessionId: String, root: String?): String? {
        val r = runCatching { run(sessionId, pullCmd(root), timeoutMs = 60_000L) }
            .getOrElse { return it.message ?: "shell unavailable" }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    /**
     * Whitelisted one-verb executor for the branch switcher. Only `checkout`
     * is allowed today; anything else is refused without touching the shell.
     *
     * @return error text, or null on success.
     */
    suspend fun execSimple(sessionId: String, vararg argv: String, root: String? = null): String? {
        val allowed = setOf("checkout")
        val verb = argv.getOrNull(0) ?: return "no command"
        if (verb !in allowed) return "command not allowed: $verb"
        val r = runCatching {
            run(sessionId, gitPrefix(root) + " " + argv.joinToString(" ") { shellQuote(it) }, 30_000L)
        }.getOrElse { return it.message ?: "shell unavailable" }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    /** Push URL of the first remote, or null when the repo has none. */
    suspend fun remote(sessionId: String, root: String? = null): String? {
        val r = runCatching { run(sessionId, remoteCmd(root)) }.getOrElse { return null }
        return if (r.exitCode == 0) parseRemote(r.output) else null
    }

    suspend fun commitDetail(
        sessionId: String,
        hash: String,
        root: String? = null,
    ): GitCommitDetail? {
        val r = runCatching { run(sessionId, commitDetailCmd(hash, root)) }
            .getOrElse { return null }
        return if (r.exitCode == 0) parseCommitDetail(r.output) else null
    }

    suspend fun commitNumstat(
        sessionId: String,
        hash: String,
        root: String? = null,
    ): List<CommitFileStat> {
        val r = runCatching { run(sessionId, commitNumstatCmd(hash, root)) }
            .getOrElse { return emptyList() }
        return if (r.exitCode == 0) parseCommitNumstat(r.output) else emptyList()
    }

    /** Single-file diff inside one commit; feeds the same diff view as fileDiff. */
    suspend fun commitFileDiff(
        sessionId: String,
        hash: String,
        path: String,
        root: String? = null,
    ): String {
        val r = runCatching { run(sessionId, commitFileDiffCmd(hash, path, root)) }
            .getOrElse { return "[diff unavailable: ${it.message}]" }
        if (r.exitCode != 0) return "[git show exit=${r.exitCode}]\n${r.output.take(2000)}"
        return truncateDiff(r.output.ifBlank { "[empty diff]" })
    }

    /** Working-tree diff for one entry; untracked files diff against /dev/null. */
    suspend fun fileDiff(sessionId: String, entry: GitFileEntry, root: String? = null): String {
        val path = shellQuote(entry.path)
        val g = gitPrefix(root)
        val cmd = when {
            entry.untracked ->
                "$g -c core.quotepath=false diff --no-index -- /dev/null $path"
            entry.staged ->
                "$g -c core.quotepath=false diff --cached -- $path"
            else ->
                "$g -c core.quotepath=false diff -- $path"
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

    suspend fun stagedDiff(sessionId: String, root: String? = null): String {
        val r = runCatching {
            run(sessionId, "${gitPrefix(root)} -c core.quotepath=false diff --cached")
        }.getOrElse { return "" }
        return if (r.exitCode == 0) r.output else ""
    }

    suspend fun commitDiff(sessionId: String, hash: String, root: String? = null): String {
        val quoted = shellQuote(hash)
        val r = runCatching {
            run(sessionId, "${gitPrefix(root)} -c core.quotepath=false show --date=iso $quoted")
        }.getOrElse { return "[diff unavailable: ${it.message}]" }
        if (r.exitCode != 0) return "[git show exit=${r.exitCode}]\n${r.output.take(2000)}"
        return truncateDiff(r.output)
    }

    /** @return error text, or null on success. */
    suspend fun setStaged(
        sessionId: String,
        entry: GitFileEntry,
        staged: Boolean,
        root: String? = null,
    ): String? {
        val path = shellQuote(entry.path)
        val g = gitPrefix(root)
        val cmd = if (staged) "$g add -- $path" else "$g restore --staged -- $path"
        val r = runCatching { run(sessionId, cmd) }.getOrElse {
            return it.message ?: "shell unavailable"
        }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    suspend fun stageAll(sessionId: String, root: String? = null): String? {
        val r = runCatching { run(sessionId, "${gitPrefix(root)} add -A") }.getOrElse {
            return it.message ?: "shell unavailable"
        }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    /** @return [CommitOutcome]; failure text comes straight from git. */
    suspend fun commit(sessionId: String, message: String, root: String? = null): CommitOutcome {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return CommitOutcome(false, "empty commit message")
        val b64 = android.util.Base64.encodeToString(
            trimmed.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val r = runCatching { run(sessionId, buildCommitCommand(b64, root)) }.getOrElse {
            return CommitOutcome(false, it.message ?: "shell unavailable")
        }
        return if (r.exitCode == 0) CommitOutcome(true, r.output.trim())
        else CommitOutcome(false, r.output.trim().ifBlank { "git commit exit=${r.exitCode}" })
    }
}
