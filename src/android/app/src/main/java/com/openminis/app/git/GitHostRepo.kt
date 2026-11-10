package com.openminis.app.git

import com.openminis.app.sandbox.SessionWorkspace
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * [T-git-panel-native] Host-native git operations for the Git panel, backed by
 * JGit against the session workspace directory on the HOST filesystem.
 *
 * WHY: the sandbox path (`GitCli`) runs every git command through the PRoot
 * shell — boot + shell round-trip + execution means the panel shows a spinner
 * for seconds on entry (and shows nothing when the sandbox is in a bad state).
 * The workspace is a real directory on the host filesystem (bind-mounted into
 * PRoot), so a repo created in the sandbox is equally visible to JGit here:
 * read operations become INSTANT, with no sandbox dependency.
 *
 * Read operations (log/status/branches/stashes/remote/diff) live here.
 * Mutations (commit/stage) map onto the same `GitCli` data classes so the
 * ViewModel and UI stay unchanged; push/pull stay on the sandbox path
 * (credentials live in the sandbox git config).
 *
 * All methods are synchronous — callers wrap in coroutines.
 */
object GitHostRepo {

    /** Host directory of the session workspace (bind root of /var/minis/workspace). */
    fun workspaceDir(filesDir: File, sessionId: String): File =
        SessionWorkspace.hostDir(filesDir, sessionId, "workspace")

    /**
     * Open the repo at [dir]; null when the directory is not a git work tree.
     * HEAD is deliberately NOT checked here: a repo whose HEAD points at a
     * missing object is still readable (status/branches/diff all work), and
     * the sandbox path serves it via the per-ref fallback — the host path
     * must too ([logPage] walks refs when HEAD is unresolvable). The handle
     * is closed on the failure path so a failed open cannot leak a
     * Repository (the panel opens one per refresh).
     */
    private fun open(dir: File): Git? = try {
        val g = Git.open(dir)
        runCatching { g.repository.repositoryState }.fold(
            onSuccess = { g },
            onFailure = { runCatching { g.close() }; null },
        )
    } catch (_: Exception) {
        null
    }

    /** First resolvable ref sha walking HEAD → every ref, or null. */
    private fun resolveHeadOrAnyRef(repo: Repository): ObjectId? {
        // HEAD's sha can resolve while its OBJECT is missing (resolve only
        // reads the ref file) — validate by parsing, then fall to other refs.
        runCatching {
            repo.resolve(Constants.HEAD)?.let { head ->
                RevWalk(repo).use { it.parseCommit(head) }
                return head
            }
        }
        for (ref in repo.refDatabase.getRefs()) {
            val id = ref.objectId ?: continue
            if (id == ObjectId.zeroId()) continue
            if (runCatching { RevWalk(repo).use { it.parseCommit(id) } }.isSuccess) return id
        }
        return null
    }

    // ── probe ───────────────────────────────────────────────────────────────

    /**
     * Repo identity for the header card, or isRepo=false outside a work tree.
     * A broken HEAD object does not demote the repo: any resolvable ref keeps
     * it servable ([logPage] walks refs, sandbox-fallback parity).
     */
    fun probe(dir: File): GitCli.GitRepoInfo {
        val git = open(dir) ?: return GitCli.GitRepoInfo(isRepo = false)
        git.use { g ->
            val repo = g.repository
            val hasAnchor = resolveHeadOrAnyRef(repo) != null
            if (!hasAnchor) return GitCli.GitRepoInfo(isRepo = false)
            val branch = runCatching { repo.branch }.getOrDefault("")
            val cfg = repo.config
            return GitCli.GitRepoInfo(
                isRepo = true,
                root = dir.absolutePath,
                branch = branch,
                userName = cfg.getString("user", null, "name"),
                userEmail = cfg.getString("user", null, "email"),
            )
        }
    }

    // ── log ─────────────────────────────────────────────────────────────────

    /**
     * One history page, newest first, with parent hashes for the graph.
     * Maps JGit RevCommit onto [GitCli.GitCommit] so the ViewModel/UI stay
     * unchanged across the host/sandbox switch.
     */
    fun logPage(dir: File, offset: Int, limit: Int): List<GitCli.GitCommit> {
        val git = open(dir) ?: return emptyList()
        git.use { g ->
            val walk = RevWalk(g.repository)
            walk.isRetainBody = true
            // Deterministic pagination: `git log -n N --skip M` walks the
            // default order, newest commit first. Sorting explicitly keeps
            // offset/limit stable across refreshes.
            walk.sort(RevSort.COMMIT_TIME_DESC)
            try {
                // HEAD first; a repo with a broken HEAD object still serves
                // history from its refs (sandbox path's per-ref fallback).
                val anchor = resolveHeadOrAnyRef(g.repository) ?: return emptyList()
                walk.markStart(walk.parseCommit(anchor))
                val commits = ArrayList<GitCli.GitCommit>(limit)
                var skipped = 0
                for (rev in walk) {
                    if (skipped < offset) {
                        skipped++
                        continue
                    }
                    if (commits.size >= limit) break
                    commits += GitCli.GitCommit(
                        hash = rev.name,
                        shortHash = rev.abbreviate(8).name(),
                        author = rev.authorIdent.name,
                        epochSeconds = rev.commitTime.toLong(),
                        subject = rev.shortMessage,
                        parents = rev.parents.map { it.name },
                    )
                }
                return commits
            } finally {
                walk.dispose()
            }
        }
    }

    // ── status ──────────────────────────────────────────────────────────────

    /** Working-tree status with ahead/behind, mapped onto [GitCli.GitStatus]. */
    fun status(dir: File): GitCli.GitStatus {
        val git = open(dir) ?: return GitCli.GitStatus(error = "not a git repository")
        git.use { g ->
            val repo = g.repository
            val s = g.status().call()
            val entries = ArrayList<GitCli.GitFileEntry>()
            // Direct set mapping mirrors porcelain XY columns exactly:
            // staged = added/changed/removed, unstaged = modified/missing,
            // conflicts emit both rows ("UU" -> two 'U' entries), untracked '?'.
            // Overlaps are meaningful: an AM/MM file sits in both sets and
            // must produce both rows — deduping them (the old
            // uncommittedChanges loop) lost the unstaged row.
            for (p in s.added) entries += GitCli.GitFileEntry(p, code = 'A', staged = true)
            for (p in s.changed) entries += GitCli.GitFileEntry(p, code = 'M', staged = true)
            for (p in s.removed) entries += GitCli.GitFileEntry(p, code = 'D', staged = true)
            for (p in s.modified) entries += GitCli.GitFileEntry(p, code = 'M', staged = false)
            for (p in s.missing) entries += GitCli.GitFileEntry(p, code = 'D', staged = false)
            for (p in s.conflicting) {
                entries += GitCli.GitFileEntry(p, code = 'U', staged = true)
                entries += GitCli.GitFileEntry(p, code = 'U', staged = false)
            }
            for (p in s.untracked) entries += GitCli.GitFileEntry(p, code = '?', staged = false, untracked = true)
            entries.sortWith(compareBy({ it.path }, { !it.staged }))
            val (ahead, behind, upstream) = aheadBehind(repo)
            return GitCli.GitStatus(ahead = ahead, behind = behind, upstream = upstream, entries = entries)
        }
    }

    /** Ahead/behind vs the tracked upstream; (0,0,null) when none. */
    private fun aheadBehind(repo: Repository): Triple<Int, Int, String?> {
        val branch = repo.branch ?: return Triple(0, 0, null)
        val remote = repo.config.getString("branch", branch, "remote")
        val merge = repo.config.getString("branch", branch, "merge")
        val upstreamName = if (remote != null && merge != null) {
            "$remote/${merge.removePrefix("refs/heads/")}"
        } else null
        ?: return Triple(0, 0, null)
        val upstreamId = repo.resolve(upstreamName) ?: return Triple(0, 0, upstreamName)
        val headId = repo.resolve(Constants.HEAD) ?: return Triple(0, 0, upstreamName)
        val aheadWalk = RevWalk(repo).apply { isRetainBody = false }
        val behindWalk = RevWalk(repo).apply { isRetainBody = false }
        try {
            aheadWalk.markStart(aheadWalk.parseCommit(headId))
            aheadWalk.markUninteresting(aheadWalk.parseCommit(upstreamId))
            val ahead = aheadWalk.asSequence().count()
            behindWalk.markStart(behindWalk.parseCommit(upstreamId))
            behindWalk.markUninteresting(behindWalk.parseCommit(headId))
            val behind = behindWalk.asSequence().count()
            return Triple(ahead, behind, upstreamName)
        } finally {
            aheadWalk.dispose()
            behindWalk.dispose()
        }
    }

    // ── branches ────────────────────────────────────────────────────────────

    /** Local + remote branches with the last commit subject per branch. */
    fun branches(dir: File): List<GitCli.GitBranchRef> {
        val git = open(dir) ?: return emptyList()
        git.use { g ->
            val repo = g.repository
            val result = ArrayList<GitCli.GitBranchRef>()
            val branchList = g.branchList().call()
            val walk = RevWalk(repo)
            walk.isRetainBody = false
            try {
                for (ref in branchList) {
                    val commit = try {
                        walk.parseCommit(ref.objectId).shortMessage
                    } catch (_: Exception) {
                        ""
                    }
                    result += GitCli.GitBranchRef(
                        name = ref.name.removePrefix("refs/heads/"),
                        isCurrent = ref.name == repo.fullBranch,
                        lastSubject = commit,
                    )
                }
                // Remote-tracking branches, newest commit first (the sandbox
                // sorts by -committerdate desc; packed-refs order is arbitrary),
                // capped to keep the switcher readable.
                val remoteRefs = repo.refDatabase.getRefsByPrefix("refs/remotes/origin/")
                val byRecency = remoteRefs.sortedByDescending { ref ->
                    try {
                        walk.parseCommit(ref.objectId).commitTime
                    } catch (_: Exception) {
                        0
                    }
                }
                for (ref in byRecency.take(10)) {
                    val short = ref.name.removePrefix("refs/remotes/origin/")
                    if (short == "HEAD") continue
                    val commit = try {
                        walk.parseCommit(ref.objectId).shortMessage
                    } catch (_: Exception) {
                        ""
                    }
                    result += GitCli.GitBranchRef(
                        name = "origin/$short",
                        isCurrent = false,
                        lastSubject = commit,
                    )
                }
            } finally {
                walk.dispose()
            }
            return result.distinctBy { it.name }
        }
    }

    // ── stashes ─────────────────────────────────────────────────────────────

    fun stashes(dir: File): List<GitCli.GitStashEntry> {
        val git = open(dir) ?: return emptyList()
        git.use { g ->
            val result = ArrayList<GitCli.GitStashEntry>()
            var i = 0
            for (entry in g.stashList().call()) {
                result += GitCli.GitStashEntry(index = i, subject = entry.shortMessage ?: "")
                i++
            }
            return result
        }
    }

    // ── remote ──────────────────────────────────────────────────────────────

    /** First remote's push URL, or null. */
    fun remote(dir: File): String? {
        val git = open(dir) ?: return null
        git.use { g ->
            val cfg = g.repository.config
            for (name in cfg.getSubsections("remote")) {
                cfg.getString("remote", name, "pushurl")?.let { return it }
                cfg.getString("remote", name, "url")?.let { return it }
            }
            return null
        }
    }

    // ── workspace discovery ─────────────────────────────────────────────────

    /**
     * Host-side counterpart of `GitCliCmd.DISCOVER_CMD`: the session workspace
     * itself, or a repo nested one or two levels below it
     * (`workspace/<repo>`, `workspace/<group>/<repo>`). Ties break by most
     * recently active (`.git/HEAD` mtime — every commit and checkout touches
     * it), mirroring the sandbox discovery's activity signal. Returns the
     * host root plus its path relative to the workspace ("" = workspace
     * itself); null when no repo is found.
     */
    fun locate(workspaceDir: File): Pair<File, String>? {
        if (!workspaceDir.isDirectory) return null
        if (File(workspaceDir, ".git").exists()) return workspaceDir to ""
        val candidates = ArrayList<Pair<File, String>>()
        val level1 = workspaceDir.listFiles(File::isDirectory) ?: emptyArray()
        for (group in level1) {
            val dotGit = File(group, ".git")
            if (dotGit.exists()) candidates += group to group.name
            val level2 = group.listFiles(File::isDirectory) ?: emptyArray()
            for (repo in level2) {
                if (File(repo, ".git").exists()) candidates += repo to "${group.name}/${repo.name}"
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { (dir, _) ->
            File(dir, ".git/HEAD").takeIf { it.isFile }?.lastModified() ?: 0L
        }
    }

    /** Guest-side absolute path of a repo discovered under the workspace. */
    fun guestPath(relative: String): String =
        if (relative.isBlank()) GUEST_WORKSPACE else "$GUEST_WORKSPACE/$relative"

    /** The workspace's fixed mount point inside the PRoot sandbox. */
    const val GUEST_WORKSPACE = "/var/minis/workspace"

    // ── numstat (staged / unstaged) ─────────────────────────────────────────

    /**
     * Numstat rows for the staged diff (HEAD vs index), keyed by path.
     * The index side is a [DirCacheIterator] — `dc.writeTree(inserter)` would
     * write a fresh loose tree object into .git/objects on EVERY refresh,
     * polluting the user's repository object database with garbage.
     */
    fun numstatStaged(dir: File): Map<String, GitCli.NumStat> {
        val git = open(dir) ?: return emptyMap()
        git.use { g ->
            val repo = g.repository
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            try {
                val headId = repo.resolve(Constants.HEAD)
                val dc = DirCache.read(repo)
                if (headId != null) {
                    repo.newObjectReader().use { reader ->
                        val headTree = RevWalk(repo).use { it.parseCommit(headId).tree }
                        df.format(
                            CanonicalTreeParser().apply { reset(reader, headTree) },
                            DirCacheIterator(dc),
                        )
                    }
                } else {
                    df.format(EmptyTreeIterator(), DirCacheIterator(dc))
                }
                return parseNumstatText(baos.toString(Charsets.UTF_8.name()))
            } catch (_: Exception) {
                return emptyMap()
            } finally {
                df.close()
            }
        }
    }

    /** Numstat rows for the unstaged diff (index vs working tree), keyed by path. */
    fun numstatUnstaged(dir: File): Map<String, GitCli.NumStat> {
        val git = open(dir) ?: return emptyMap()
        git.use { g ->
            val repo = g.repository
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            try {
                val dc = DirCache.read(repo)
                // ADD in the index-vs-worktree scan = a worktree file with no
                // index entry = untracked. The sandbox `git diff --numstat`
                // never lists untracked files — filter them out.
                val entries = df.scan(DirCacheIterator(dc), FileTreeIterator(repo))
                    .filter { it.changeType != DiffEntry.ChangeType.ADD }
                df.format(entries)
            } catch (_: Exception) {
                return emptyMap()
            } finally {
                df.close()
            }
            return parseNumstatText(baos.toString(Charsets.UTF_8.name()))
        }
    }

    /**
     * Staged diff text (HEAD vs index) for AI commit-message generation.
     * On an unborn HEAD (first commit) the staged files are listed as added —
     * exactly what the sandbox `git diff --cached` prints, and the generator
     * needs that content to write a useful message.
     */
    fun stagedDiff(dir: File): String {
        val git = open(dir) ?: return ""
        git.use { g ->
            val repo = g.repository
            val headId = repo.resolve(Constants.HEAD)
            val dc = DirCache.read(repo)
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            try {
                if (headId != null) {
                    repo.newObjectReader().use { reader ->
                        val headTree = RevWalk(repo).use { it.parseCommit(headId).tree }
                        df.format(
                            CanonicalTreeParser().apply { reset(reader, headTree) },
                            DirCacheIterator(dc),
                        )
                    }
                } else {
                    df.format(EmptyTreeIterator(), DirCacheIterator(dc))
                }
            } catch (_: Exception) {
                return ""
            } finally {
                df.close()
            }
            val text = baos.toString(Charsets.UTF_8.name())
            return if (text.length > DIFF_MAX_CHARS) {
                text.take(DIFF_MAX_CHARS) + System.lineSeparator() + "…"
            } else {
                text
            }
        }
    }

    private const val DIFF_MAX_CHARS = 200_000

    /**
     * Parse a unified diff text into per-file numstat. Paths come from the
     * `--- a/` / `+++ b/` header lines (exact even when a path itself
     * contains " b/", which the old `substringAfterLast(" b/")` mangled), a
     * `Binary files` marker sets the binary flag the way the sandbox numstat
     * prints `-\t-\t<path>`, and +/- lines are only counted inside hunks so
     * content lines that start with `+++`/`---` count as content, not headers.
     */
    internal fun parseNumstatText(text: String): Map<String, GitCli.NumStat> {
        val result = HashMap<String, GitCli.NumStat>()
        var currentPath: String? = null
        var added = 0
        var deleted = 0
        var binary = false
        var inHunk = false
        fun flush() {
            currentPath?.let { result[it] = GitCli.NumStat(added, deleted, binary) }
            currentPath = null
            added = 0; deleted = 0; binary = false; inHunk = false
        }
        for (line in text.lines()) {
            when {
                line.startsWith("diff --git") -> {
                    flush()
                    currentPath = headerPath(line)
                }
                !inHunk && line.startsWith("+++ b/") ->
                    currentPath = line.removePrefix("+++ b/")
                !inHunk && line.startsWith("--- a/") && currentPath == null ->
                    currentPath = line.removePrefix("--- a/") // deletion: +++ is /dev/null
                !inHunk && line.startsWith("Binary files") -> binary = true
                line.startsWith("@@") -> inHunk = true
                inHunk && line.startsWith("+") -> added++
                inHunk && line.startsWith("-") -> deleted++
            }
        }
        flush()
        return result
    }

    /**
     * Path from a `diff --git a/X b/Y` header. DiffFormatter runs without
     * rename detection, so X == Y and the body is `a/P b/P` — the path is
     * the first half of the remainder. Null when the shape differs.
     */
    private fun headerPath(line: String): String? {
        val body = line.removePrefix("diff --git ")
        if (!body.startsWith("a/")) return null
        val rest = body.removePrefix("a/")
        val split = (rest.length - 3) / 2
        if (split <= 0 || !rest.startsWith(" b/", split)) return null
        return rest.substring(0, split)
    }

    /**
     * Slice one file's unified-diff section out of a multi-file diff: from
     * the file's `diff --git` header to the next `diff --git` header (or the
     * end). Works for deletions (`+++ /dev/null`) and binary files (no `@@`
     * hunks at all), both of which broke the old `@@`-anchored extraction.
     */
    internal fun diffSection(full: String, path: String): String? {
        val header = "diff --git a/$path b/$path"
        var idx = full.indexOf(header)
        while (idx >= 0) {
            // Only a header at line start counts; a content line could echo it.
            if (idx == 0 || full[idx - 1] == '\n') {
                val end = full.indexOf("\ndiff --git", idx + header.length)
                return full.substring(idx, if (end >= 0) end else full.length)
            }
            idx = full.indexOf(header, idx + 1)
        }
        return null
    }




    // ── commit detail ───────────────────────────────────────────────────────

    /** Full metadata for the commit-detail sheet. */
    fun commitDetail(dir: File, hash: String): GitCli.GitCommitDetail? {
        val git = open(dir) ?: return null
        git.use { g ->
            val repo = g.repository
            val id = repo.resolve(hash) ?: return null
            val rev = RevWalk(repo).use { it.parseCommit(id) }
            val author = rev.authorIdent
            val committer = rev.committerIdent
            val body = rev.fullMessage.removePrefix(rev.shortMessage).trimStart()
            return GitCli.GitCommitDetail(
                hash = rev.name,
                author = author.name,
                authorEmail = author.emailAddress,
                authorEpoch = author.getWhen().time / 1000,
                committer = committer.name,
                committerEmail = committer.emailAddress,
                committerEpoch = committer.getWhen().time / 1000,
                subject = rev.shortMessage,
                body = body,
            )
        }
    }

    /** Per-file stats of one commit (git show --numstat equivalent). */
    fun commitNumstat(dir: File, hash: String): List<GitCli.CommitFileStat> {
        val git = open(dir) ?: return emptyList()
        git.use { g ->
            val repo = g.repository
            val id = repo.resolve(hash) ?: return emptyList()
            val walk = RevWalk(repo)
            try {
                val commit = walk.parseCommit(id)
                val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
                val baos = ByteArrayOutputStream()
                val df = DiffFormatter(baos)
                df.setRepository(repo)
                try {
                    if (parent != null) {
                        df.format(parent.tree, commit.tree)
                    } else {
                        formatRootDiff(df, repo, commit.tree)
                    }
                    return parseNumstatText(baos.toString(Charsets.UTF_8.name())).map { (path, stat) ->
                        GitCli.CommitFileStat(path, stat.added, stat.deleted, stat.binary)
                    }
                } finally {
                    df.close()
                }
            } finally {
                walk.dispose()
            }
        }
    }



    // ── diff of one file in one commit ──────────────────────────────────────

    /**
     * Unified diff of [path] at [hash] vs its first parent. The file's own
     * section is sliced between its `diff --git` header and the next one —
     * the old `@@`-anchored extraction leaked the rest of the commit's diff
     * into this file's view, and deletions (no `+++ b/` marker) or binary
     * files (no hunks at all) returned the wrong range or nothing.
     */
    fun commitFileDiff(dir: File, hash: String, path: String): String {
        val git = open(dir) ?: return "(diff unavailable)"
        git.use { g ->
            val repo = g.repository
            val id = repo.resolve(hash) ?: return "(unknown commit)"
            val walk = RevWalk(repo)
            try {
                val commit = walk.parseCommit(id)
                val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
                val baos = ByteArrayOutputStream()
                val df = DiffFormatter(baos)
                df.setRepository(repo)
                try {
                    if (parent != null) {
                        df.format(parent.tree, commit.tree)
                    } else {
                        formatRootDiff(df, repo, commit.tree)
                    }
                } finally {
                    df.close()
                }
                val full = baos.toString(Charsets.UTF_8.name())
                return diffSection(full, path) ?: "(no diff for $path)"
            } finally {
                walk.dispose()
            }
        }
    }



    // ── diff of a working-tree file ─────────────────────────────────────────

    /** Unified diff of one working-tree file vs HEAD. */
    /**
     * Unified diff of one working-tree entry, mirroring the sandbox's three
     * commands: untracked → `--no-index /dev/null` (whole file added),
     * staged → `--cached` (HEAD vs index), unstaged → index vs worktree.
     * Binary content short-circuits to the same text `git diff` prints, the
     * worktree read is capped, and the output truncates like the sandbox.
     */
    fun fileDiff(dir: File, path: String, staged: Boolean, untracked: Boolean): String {
        val git = open(dir) ?: return "(diff unavailable)"
        git.use { g ->
            val repo = g.repository
            val headBytes = repo.resolve(Constants.HEAD)?.let { h ->
                RevWalk(repo).use { walk ->
                    TreeWalk.forPath(repo, path, walk.parseCommit(h).tree)
                        ?.let { tw -> repo.open(tw.getObjectId(0)).bytes }
                }
            }
            val indexBytes = DirCache.read(repo).getEntry(path)?.let { repo.open(it.objectId).bytes }
            val workBytes = readWorkBytes(File(dir, path))
            val oldBytes: ByteArray
            val newBytes: ByteArray
            when {
                untracked -> { oldBytes = ByteArray(0); newBytes = workBytes }
                staged -> { oldBytes = headBytes ?: ByteArray(0); newBytes = indexBytes ?: ByteArray(0) }
                else -> { oldBytes = indexBytes ?: ByteArray(0); newBytes = workBytes }
            }
            if (isBinary(oldBytes) || isBinary(newBytes)) {
                return "Binary files a/$path and b/$path differ"
            }
            val oldText = RawText(oldBytes)
            val newText = RawText(newBytes)
            val edits = histogram().diff(RawTextComparator.DEFAULT, oldText, newText)
            val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
            for (e in edits) {
                sb.append("@@ -${e.beginA + 1},${e.endA - e.beginA} +${e.beginB + 1},${e.endB - e.beginB} @@\n")
                for (i in e.beginA until e.endA) sb.append("-").append(oldText.getString(i)).append("\n")
                for (i in e.beginB until e.endB) sb.append("+").append(newText.getString(i)).append("\n")
            }
            val text = sb.toString()
            return if (text.length > DIFF_MAX_CHARS) {
                text.take(DIFF_MAX_CHARS) + System.lineSeparator() + "…"
            } else {
                text
            }
        }
    }

    /** Worktree bytes capped at 8 MB — one huge asset cannot OOM the panel. */
    private fun readWorkBytes(file: File): ByteArray {
        if (!file.isFile) return ByteArray(0)
        val cap = 8 * 1024 * 1024
        val size = file.length()
        if (size <= cap) return file.readBytes()
        val buf = ByteArray(cap)
        FileInputStream(file).use { it.readNBytes(buf, 0, cap) }
        return buf
    }


    // ── commit diff (whole commit) ──────────────────────────────────────────

    /** Unified diff of [hash] vs its first parent (concatenated per file). */
    fun commitDiff(dir: File, hash: String): String {
        val git = open(dir) ?: return "(diff unavailable)"
        git.use { g ->
            val repo = g.repository
            val id = repo.resolve(hash) ?: return "(no changes)"
            val walk = RevWalk(repo)
            try {
                val commit = walk.parseCommit(id)
                val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
                val baos = ByteArrayOutputStream()
                val df = DiffFormatter(baos)
                df.setRepository(repo)
                try {
                    if (parent != null) {
                        df.format(parent.tree, commit.tree)
                    } else {
                        formatRootDiff(df, repo, commit.tree)
                    }
                } finally {
                    df.close()
                }
                return baosToString(baos)
            } finally {
                walk.dispose()
            }
        }
    }

    /**
     * Format a root commit's full diff: the empty tree as the old side, so
     * every file is listed as added. The object reader lives exactly as long
     * as the format call that reads through it — the old treeParser leaked it.
     */
    private fun formatRootDiff(df: DiffFormatter, repo: Repository, treeId: ObjectId) {
        repo.newObjectReader().use { reader ->
            df.format(EmptyTreeIterator(), CanonicalTreeParser().apply { reset(reader, treeId) })
        }
    }

    // ── stage / unstage (host-native) ───────────────────────────────────────

    /** Stage or unstage one file. */
    fun setStaged(dir: File, path: String, staged: Boolean) {
        val git = open(dir) ?: return
        git.use { g ->
            if (staged) {
                g.add().addFilepattern(path).call()
            } else {
                // `rm --cached` schedules the file for DELETION on the next
                // commit and untracks it — unstage must never destroy staged
                // content. `reset -- <path>` is the git restore --staged
                // equivalent: a staged edit becomes an unstaged edit, a
                // staged add becomes an untracked file.
                g.reset().addPath(path).call()
            }
        }
    }

    /** Stage everything (added/changed/deleted). */
    fun stageAll(dir: File) {
        val git = open(dir) ?: return
        git.use { g ->
            g.add().addFilepattern(".").setUpdate(true).call()
            g.add().addFilepattern(".").call()
        }
    }

    /**
     * PersonIdent for new commits: repo-local config wins (git's own
     * resolution), the sandbox identity fills the gap, and the default keeps
     * the commit authorable on machines with neither.
     */
    fun commitIdentity(dir: File, sandboxName: String?, sandboxEmail: String?): PersonIdent {
        val git = open(dir) ?: return PersonIdent(sandboxName ?: "Minis", sandboxEmail ?: "minis@local")
        git.use { g ->
            val cfg = g.repository.config
            val name = cfg.getString("user", null, "name") ?: sandboxName ?: "Minis"
            val email = cfg.getString("user", null, "email") ?: sandboxEmail ?: "minis@local"
            return PersonIdent(name, email)
        }
    }

    /**
     * Create a commit with the given message and identity. Returns the same
     * [GitCli.CommitOutcome] the sandbox path produces so the ViewModel keeps
     * one code shape for both sources.
     */
    fun commit(dir: File, message: String, identity: PersonIdent): GitCli.CommitOutcome {
        val git = open(dir) ?: return GitCli.CommitOutcome(false, "not a git repository")
        git.use { g ->
            return try {
                g.commit().setMessage(message).setAuthor(identity).setCommitter(identity).call()
                GitCli.CommitOutcome(true, "")
            } catch (e: Exception) {
                GitCli.CommitOutcome(false, e.message ?: "commit failed")
            }
        }
    }

    private fun baosToString(baos: ByteArrayOutputStream): String =
        baos.toString(Charsets.UTF_8.name())

    private fun histogram() =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)

    private fun isBinary(bytes: ByteArray): Boolean {
        val cap = minOf(bytes.size, 8000)
        for (i in 0 until cap) if (bytes[i] == 0.toByte()) return true
        return false
    }

}
