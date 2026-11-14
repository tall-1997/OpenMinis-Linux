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
 * Parsers are pure and unit-tested (GitCliParseTest); command builders
 * live in [GitCliCmd] (pure string functions, also JVM-unit-tested) and
 * the shell layer is a thin wrapper that only quotes paths and routes
 * through them, so every git flag and escape stays machine-checkable
 * (`--porcelain`, `%x1f` field separators, `-c core.quotepath=false` so
 * unicode paths arrive unescaped).
 */
object GitCli {

    /** Log tag shared by the panel's probe chain (ViewModel uses it too). */
    private const val TAG = "GitPanel"

    /** Diff cap keeps a huge `git show` from blowing the Compose text node. */
    private const val DIFF_MAX_CHARS = 200_000

    /** A repo found by [GitCliCmd.DISCOVER_CMD]: absolute root path + display name. */
    data class DiscoveredRepo(
        /** Absolute guest path of the repo root (the directory containing `.git`). */
        val path: String,
        /** Display name: basename of [path] (shown in the panel header). */
        val name: String,
    )

    /**
     * Discovery lines are absolute repo-root paths, most recently active
     * first (see [GitCliCmd.DISCOVER_CMD]). Blank lines and stray text are
     * dropped; order is preserved because it is the activity ranking.
     */
    fun parseDiscover(output: String): List<DiscoveredRepo> = output.lines()
        .map { it.trim() }
        .filter { it.startsWith("/") }
        .map { DiscoveredRepo(path = it, name = it.substringAfterLast('/')) }
        .filter { it.name.isNotBlank() }

    // ---------------------------------------------------------------- models

    data class GitRepoInfo(
        val isRepo: Boolean,
        val root: String = "",
        val branch: String = "",
        val userName: String? = null,
        val userEmail: String? = null,
        val error: String? = null,
        /**
         * Repo display name (basename) when adopted via workspace
         * discovery; empty when cwd itself was the repo.
         */
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
        /**
         * [T-git-panel-zcode-graph] `%D` decorations: `HEAD`, local branch
         * short names, `origin/<name>`, tag short names — the badges the
         * graph description column renders (ZCode parity).
         */
        val refs: List<String> = emptyList(),
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
        /** Tracking branch short name (`origin/main`); null when untracked. */
        val upstream: String? = null,
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

    /**
     * `hash<x1f>name<x1f>subject<x1f>symref` rows → switcher entries;
     * garbage rows drop. The optional 4th column carries `%(symref)` from
     * [GitCliCmd.branchesCmd]: symbolic refs (origin/HEAD) are non-empty there and
     * are skipped — listing them would offer an unswitchable alias row.
     */
    fun parseBranches(output: String, current: String): List<GitBranchRef> =
        output.lines().mapNotNull { raw ->
            val parts = raw.trim().split('\u001f')
            if (parts.size < 2) return@mapNotNull null
            val name = parts[1].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (parts.getOrNull(3)?.isNotBlank() == true) return@mapNotNull null
            GitBranchRef(
                name = name,
                isCurrent = name == current,
                lastSubject = parts.getOrNull(2).orEmpty(),
                // %(upstream:short) column (ZCode for-each-ref contract);
                // blank for untracked branches and remote refs alike.
                upstream = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
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
     * Graph record WITH the `%D` decoration column (ZCode contract): the
     * refs column is field 6 (the format pins one record per line, so the
     * subject cannot bleed into it). `HEAD -> main` expands to the HEAD and
     * main badges; `tag: v1` strips the prefix; blank → no badges.
     */
    fun parseGraphLogWithRefs(output: String): List<GitCommit> = output.lines().mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank()) return@mapNotNull null
        val f = line.split('\u001f')
        if (f.size < 7) return@mapNotNull null
        val refs = f[6].split(',').flatMap { token ->
            val t = token.trim()
            when {
                t.isBlank() -> emptyList()
                t.startsWith("HEAD ->") -> listOf("HEAD", t.substringAfter("->").trim())
                else -> listOf(t.removePrefix("tag: "))
            }
        }.distinct()
        GitCommit(
            hash = f[0],
            shortHash = f[2],
            author = f[3],
            epochSeconds = f[4].toLongOrNull() ?: 0L,
            subject = f[5],
            parents = f[1].split(' ').filter { it.isNotBlank() },
            refs = refs,
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

    /**
     * [T-git-panel-cancel-propagation] `runCatching { run(...) }` inside the
     * sandbox reads would swallow CancellationException (the execution chain
     * is real suspend code: mutex, PRoot boot, command await) and convert a
     * cancelled refresh into a bogus `isRepo=false` / empty result — the
     * caller's rethrow never fired and a stale panel painted "not a
     * repository". Every call routes through this guard: cancellation
     * propagates, any other failure degrades to a SENTINEL result
     * (exitCode = -1, error text as output) that each caller's existing
     * `exitCode != 0` branch handles exactly like a failed command.
     */
    private suspend fun gitCall(block: suspend () -> ExecutionCoordinator.CommandResult): ExecutionCoordinator.CommandResult = try {
        block()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        ExecutionCoordinator.CommandResult(
            output = e.message ?: "shell unavailable",
            exitCode = -1,
            durationMs = 0,
        )
    }

    suspend fun probe(sessionId: String): GitRepoInfo {
        // Fallback is a SENTINEL result (exit -1, error text as output): the
        // parseProbe path then reports "not a repo" with the shell error —
        // the same shape a normal non-repo probe produces.
        val r = gitCall { run(sessionId, GitCliCmd.probeCmd(), timeoutMs = 30_000L) }
        val direct = parseProbe(r.output, r.exitCode)
        if (direct.isRepo) return direct
        // cwd itself is not a repo. Fresh shells boot at /root and the agent
        // cd-s around freely, so the drifting cwd is not a stable anchor:
        // discover from the session workspace with ABSOLUTE paths (most
        // recently active first) and probe the winner by absolute path, so
        // a cd landing between the two commands cannot skew the adoption.
        // [T-git-panel-probe-retry] Each hop below used to bail straight to
        // `direct` on a single throw — a sandbox still booting, a mutex
        // timeout, a transient PRoot hiccup — and the panel then painted
        // "not a repository" for a workspace that HAS repos (field report:
        // it flips between found/missing across refreshes). Every hop now
        // retries once after a short backoff; the retry is free when the
        // sandbox is healthy and rescues the flaky boot window.
        val d = runDisco(sessionId, GitCliCmd.DISCOVER_CMD, "discover") ?: return direct
        val repos = parseDiscover(d.output)
        if (repos.isEmpty()) {
            android.util.Log.i(TAG, "[git-probe] discover exit=${d.exitCode} found no repos (out=${d.output.take(100)})")
            return direct
        }
        val repo = repos.first()
        val r2 = runDisco(sessionId, GitCliCmd.probeCmd(repo.path), "probe:${repo.name}") ?: return direct
        val nested = parseProbe(r2.output, r2.exitCode)
        return if (nested.isRepo) nested.copy(relativeRoot = repo.name) else direct
    }

    /**
     * One sandbox read of the discovery chain, retried once. Cancellation
     * propagates (same contract as [gitCall]); any other throw logs and
     * retries, returning null only after the second failure.
     */
    private suspend fun runDisco(
        sessionId: String,
        cmd: String,
        tag: String,
    ): ExecutionCoordinator.CommandResult? = try {
        run(sessionId, cmd, timeoutMs = 30_000L)
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        android.util.Log.w(TAG, "[git-probe] $tag attempt 1 threw: ${e.message?.take(120)}; retrying")
        kotlinx.coroutines.delay(150)
        try {
            run(sessionId, cmd, timeoutMs = 30_000L)
        } catch (e2: Exception) {
            android.util.Log.w(TAG, "[git-probe] $tag attempt 2 threw: ${e2.message?.take(120)}")
            null
        }
    }

    suspend fun status(sessionId: String, root: String? = null): GitStatus {
        val r = gitCall { run(sessionId, GitCliCmd.statusCmd(root)) }
        if (r.exitCode != 0) return GitStatus(error = r.output.take(500))
        return parseStatus(r.output)
    }

    suspend fun log(sessionId: String, limit: Int = 50, root: String? = null): List<GitCommit> {
        val r = gitCall { run(sessionId, GitCliCmd.logCmd(limit, root)) }
        // exit 128 = no commits yet; that is an empty history, not an error.
        return if (r.exitCode == 0) parseLog(r.output) else emptyList()
    }

    /** History page (parents + decorations included), paged: [offset] skips, [limit] caps. */
    suspend fun logPage(
        sessionId: String,
        offset: Int,
        limit: Int = 50,
        root: String? = null,
    ): List<GitCommit> {
        val r = gitCall { run(sessionId, GitCliCmd.graphLogCmd(offset, limit, root)) }
        if (r.exitCode == 0) return parseGraphLogWithRefs(r.output)
        // An unborn repo is an EMPTY history, not a failure. exit 128 is
        // git's "unresolvable revision" code: route it to empty ONLY when the
        // unborn signature confirms it — a CORRUPT HEAD also exits 128 and
        // must still reach the per-ref fallback below.
        if (r.exitCode == 128 && emptyRepoNoise(r.output)) return emptyList()
        // HEAD unresolvable (bad/missing object): `git log` and `--all` both
        // fail — walk every ref's concrete sha instead, HEAD parsing bypassed.
        val all = gitCall { run(sessionId, GitCliCmd.graphLogCmdFallback(offset, limit, root)) }
        return if (all.exitCode == 0) parseGraphLogWithRefs(all.output) else emptyList()
    }

    /** git's unborn-branch stderr signature — an empty history, not an error. */
    internal fun emptyRepoNoise(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("does not have any commits yet") ||
            lower.contains("your current branch") ||
            lower.contains("bad default revision") ||
            lower.contains("ambiguous argument 'head'")
    }

    suspend fun numstat(
        sessionId: String,
        staged: Boolean,
        root: String? = null,
    ): Map<String, NumStat> {
        val r = gitCall { run(sessionId, GitCliCmd.numstatCmd(staged, root)) }
        return if (r.exitCode == 0) parseNumstat(r.output) else emptyMap()
    }

    /** Branches for the switcher; empty (with no error) outside a repo. */
    suspend fun branches(sessionId: String, root: String?): List<GitBranchRef> {
        val r = gitCall { run(sessionId, GitCliCmd.branchesCmd(root)) }
        if (r.exitCode != 0) return emptyList()
        val current = currentBranch(sessionId, root)
        return parseBranches(r.output, current)
    }

    private suspend fun currentBranch(sessionId: String, root: String?): String =
        runCatching {
            run(sessionId, "${GitCliCmd.gitPrefix(root)} branch --show-current").output.trim()
        }.getOrDefault("")

    /** Stash list for the Changes tab; empty (with no error) outside a repo. */
    suspend fun stashes(sessionId: String, root: String?): List<GitStashEntry> {
        val r = gitCall { run(sessionId, GitCliCmd.stashCmd(root)) }
        if (r.exitCode != 0) return emptyList()
        return parseStashes(r.output)
    }

    /** @return error text, or null when the push succeeded. */
    suspend fun push(sessionId: String, root: String?): String? {
        val r = runCatching { run(sessionId, GitCliCmd.pushCmd(root), timeoutMs = 60_000L) }
            .getOrElse { return it.message ?: "shell unavailable" }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    /** @return error text, or null when the ff-only pull succeeded. */
    suspend fun pull(sessionId: String, root: String?): String? {
        val r = runCatching { run(sessionId, GitCliCmd.pullCmd(root), timeoutMs = 60_000L) }
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
            run(sessionId, GitCliCmd.gitPrefix(root) + " " + argv.joinToString(" ") { GitCliCmd.shellQuote(it) }, 30_000L)
        }.getOrElse { return it.message ?: "shell unavailable" }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    /** Push URL of the first remote, or null when the repo has none. */
    suspend fun remote(sessionId: String, root: String? = null): String? {
        val r = gitCall { run(sessionId, GitCliCmd.remoteCmd(root)) }
        return if (r.exitCode == 0) parseRemote(r.output) else null
    }

    suspend fun commitDetail(
        sessionId: String,
        hash: String,
        root: String? = null,
    ): GitCommitDetail? {
        val r = runCatching { run(sessionId, GitCliCmd.commitDetailCmd(hash, root)) }
            .getOrElse { return null }
        return if (r.exitCode == 0) parseCommitDetail(r.output) else null
    }

    suspend fun commitNumstat(
        sessionId: String,
        hash: String,
        root: String? = null,
    ): List<CommitFileStat> {
        val r = runCatching { run(sessionId, GitCliCmd.commitNumstatCmd(hash, root)) }
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
        val r = runCatching { run(sessionId, GitCliCmd.commitFileDiffCmd(hash, path, root)) }
            .getOrElse { return "[diff unavailable: ${it.message}]" }
        if (r.exitCode != 0) return "[git show exit=${r.exitCode}]\n${r.output.take(2000)}"
        return truncateDiff(r.output.ifBlank { "[empty diff]" })
    }

    /** Working-tree diff for one entry; untracked files diff against /dev/null. */
    suspend fun fileDiff(sessionId: String, entry: GitFileEntry, root: String? = null): String {
        val path = GitCliCmd.shellQuote(entry.path)
        val g = GitCliCmd.gitPrefix(root)
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
            run(sessionId, "${GitCliCmd.gitPrefix(root)} -c core.quotepath=false diff --cached")
        }.getOrElse { return "" }
        return if (r.exitCode == 0) r.output else ""
    }

    suspend fun commitDiff(sessionId: String, hash: String, root: String? = null): String {
        val quoted = GitCliCmd.shellQuote(hash)
        val r = runCatching {
            // `-m --first-parent` matches the host JGit diff, which always
            // formats against the first parent of a merge commit.
            run(sessionId, "${GitCliCmd.gitPrefix(root)} -c core.quotepath=false show -m --first-parent --date=iso $quoted")
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
        val path = GitCliCmd.shellQuote(entry.path)
        val g = GitCliCmd.gitPrefix(root)
        val cmd = if (staged) "$g add -- $path" else "$g restore --staged -- $path"
        val r = runCatching { run(sessionId, cmd) }.getOrElse {
            return it.message ?: "shell unavailable"
        }
        return if (r.exitCode == 0) null else r.output.take(500).ifBlank { "exit=${r.exitCode}" }
    }

    suspend fun stageAll(sessionId: String, root: String? = null): String? {
        val r = runCatching { run(sessionId, "${GitCliCmd.gitPrefix(root)} add -A") }.getOrElse {
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
        val r = runCatching { run(sessionId, GitCliCmd.buildCommitCommand(b64, root)) }.getOrElse {
            return CommitOutcome(false, it.message ?: "shell unavailable")
        }
        return if (r.exitCode == 0) CommitOutcome(true, r.output.trim())
        else CommitOutcome(false, r.output.trim().ifBlank { "git commit exit=${r.exitCode}" })
    }
}
