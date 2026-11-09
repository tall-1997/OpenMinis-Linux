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
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream
import java.io.File

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
     * Open the repo at [dir]; null when the directory is not a git work tree
     * or HEAD is unresolvable. The handle is closed on the failure paths so a
     * failed open cannot leak a Repository (the panel opens one per refresh).
     */
    private fun open(dir: File): Git? = try {
        val g = Git.open(dir)
        try {
            g.repository.resolve(Constants.HEAD)
            g
        } catch (_: Exception) {
            runCatching { g.close() }
            null
        }
    } catch (_: Exception) {
        null
    }

    // ── probe ───────────────────────────────────────────────────────────────

    /** Repo identity for the header card, or isRepo=false outside a work tree. */
    fun probe(dir: File): GitCli.GitRepoInfo {
        val git = open(dir) ?: return GitCli.GitRepoInfo(isRepo = false)
        git.use { g ->
            val repo = g.repository
            val branch = repo.branch ?: ""
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
            try {
                val headId = g.repository.resolve(Constants.HEAD) ?: return emptyList()
                walk.markStart(walk.parseCommit(headId))
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
            for (p in s.added) entries += GitCli.GitFileEntry(p, code = 'A', staged = true)
            for (p in s.changed) entries += GitCli.GitFileEntry(p, code = 'M', staged = true)
            for (p in s.removed) entries += GitCli.GitFileEntry(p, code = 'D', staged = true)
            for (p in s.uncommittedChanges) {
                if (p !in s.added && p !in s.changed && p !in s.removed) {
                    entries += GitCli.GitFileEntry(p, code = 'M', staged = false)
                }
            }
            for (p in s.missing) entries += GitCli.GitFileEntry(p, code = 'D', staged = false)
            for (p in s.modified) {
                if (p !in s.uncommittedChanges) entries += GitCli.GitFileEntry(p, code = 'M', staged = false)
            }
            for (p in s.untracked) entries += GitCli.GitFileEntry(p, code = '?', staged = false, untracked = true)
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
                // Remote-tracking branches: capped to keep the switcher readable.
                val remoteRefs = repo.refDatabase.getRefsByPrefix("refs/remotes/origin/")
                for (ref in remoteRefs.take(10)) {
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

    /** Numstat rows for the staged diff (HEAD vs index), keyed by path. */
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
                    df.format(repo.parseCommit(headId).tree, dc.writeTree(repo.newObjectInserter()))
                } else {
                    df.format(EmptyTreeIterator(), treeParser(repo, dc.writeTree(repo.newObjectInserter())))
                }
                return parseNumstatText(baos.toString(Charsets.UTF_8.name()))
            } catch (_: Exception) {
                return emptyMap()
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
                val entries = df.scan(DirCacheIterator(dc), FileTreeIterator(repo))
                df.format(entries)
            } catch (_: Exception) {
                return emptyMap()
            } finally {
                df.close()
            }
            return parseNumstatText(baos.toString(Charsets.UTF_8.name()))
        }
    }

    /** Staged diff text (HEAD vs index) for AI commit-message generation. */
    fun stagedDiff(dir: File): String {
        val git = open(dir) ?: return ""
        git.use { g ->
            val repo = g.repository
            val headId = repo.resolve(Constants.HEAD) ?: return ""
            val dc = DirCache.read(repo)
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            try {
                df.format(repo.parseCommit(headId).tree, dc.writeTree(repo.newObjectInserter()))
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
     * Tree iterator rooted at [treeId] — the new side of a diff against the
     * empty tree (root commits, pre-HEAD index), so `git show`-style output
     * lists every file as added instead of collapsing to an empty diff.
     */
    private fun treeParser(repo: Repository, treeId: ObjectId): CanonicalTreeParser =
        CanonicalTreeParser().apply { reset(repo.newObjectReader(), treeId) }

    /** Parse a unified diff text into per-file numstat. */
    internal fun parseNumstatText(text: String): Map<String, GitCli.NumStat> {
        val result = HashMap<String, GitCli.NumStat>()
        var currentPath: String? = null
        var added = 0
        var deleted = 0
        for (line in text.lines()) {
            when {
                line.startsWith("diff --git") -> {
                    currentPath?.let { result[it] = GitCli.NumStat(added, deleted, false) }
                    currentPath = line.substringAfterLast(" b/").trim()
                    added = 0; deleted = 0
                }
                line.startsWith("+") && !line.startsWith("+++") -> added++
                line.startsWith("-") && !line.startsWith("---") -> deleted++
            }
        }
        currentPath?.let { result[it] = GitCli.NumStat(added, deleted, false) }
        return result
    }




    // ── commit detail ───────────────────────────────────────────────────────

    /** Full metadata for the commit-detail sheet. */
    fun commitDetail(dir: File, hash: String): GitCli.GitCommitDetail? {
        val git = open(dir) ?: return null
        git.use { g ->
            val repo = g.repository
            val id = repo.resolve(hash) ?: return null
            val rev = RevWalk(repo).parseCommit(id)
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
            val commit = RevWalk(repo).parseCommit(id)
            val walk = RevWalk(repo)
            val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            try {
                if (parent != null) {
                    df.format(parent.tree, commit.tree)
                } else {
                    df.format(EmptyTreeIterator(), treeParser(repo, commit.tree))
                }
                return parseNumstatText(baos.toString(Charsets.UTF_8.name())).map { (path, stat) ->
                    GitCli.CommitFileStat(path, stat.added, stat.deleted, stat.binary)
                }
            } finally {
                df.close()
            }
        }
    }



    // ── diff of one file in one commit ──────────────────────────────────────

    /** Unified diff of [path] at [hash] vs its first parent. */
    fun commitFileDiff(dir: File, hash: String, path: String): String {
        val git = open(dir) ?: return "(diff unavailable)"
        git.use { g ->
            val repo = g.repository
            val id = repo.resolve(hash) ?: return "(unknown commit)"
            val commit = RevWalk(repo).parseCommit(id)
            val walk = RevWalk(repo)
            val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            try {
                if (parent != null) {
                    df.format(parent.tree, commit.tree)
                } else {
                    df.format(EmptyTreeIterator(), treeParser(repo, commit.tree))
                }
                val full = baos.toString(Charsets.UTF_8.name())
                val marker = "+++ b/$path\n"
                val idx = full.indexOf(marker)
                if (idx < 0) return "(no diff for $path)"
                val headerStart = full.lastIndexOf("@@", idx)
                return full.substring(if (headerStart >= 0) headerStart else 0)
            } finally {
                df.close()
            }
        }
    }



    // ── diff of a working-tree file ─────────────────────────────────────────

    /** Unified diff of one working-tree file vs HEAD. */
    fun fileDiff(dir: File, path: String): String {
        val git = open(dir) ?: return "(diff unavailable)"
        git.use { g ->
            val repo = g.repository
            val headId = repo.resolve(Constants.HEAD)
            val oldBytes = headId?.let { h ->
                TreeWalk.forPath(repo, path, RevWalk(repo).parseCommit(h).tree)
                    ?.let { tw -> repo.open(tw.getObjectId(0)).bytes }
            }
            val file = File(dir, path)
            val newBytes = if (file.isFile) file.readBytes() else ByteArray(0)
            val oldText = oldBytes?.let { RawText(it) } ?: RawText(ByteArray(0))
            val newText = RawText(newBytes)
            val edits = histogram().diff(RawTextComparator.DEFAULT, oldText, newText)
            val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
            for (e in edits) {
                sb.append("@@ -${e.beginA + 1},${e.endA - e.beginA} +${e.beginB + 1},${e.endB - e.beginB} @@\n")
                for (i in e.beginA until e.endA) sb.append("-").append(oldText.getString(i)).append("\n")
                for (i in e.beginB until e.endB) sb.append("+").append(newText.getString(i)).append("\n")
            }
            return sb.toString()
        }
    }


    // ── commit diff (whole commit) ──────────────────────────────────────────

    /** Unified diff of [hash] vs its first parent (concatenated per file). */
    fun commitDiff(dir: File, hash: String): String {
        val git = open(dir) ?: return "(diff unavailable)"
        git.use { g ->
            val repo = g.repository
            val id = repo.resolve(hash) ?: return "(no changes)"
            val commit = RevWalk(repo).parseCommit(id)
            val walk = RevWalk(repo)
            val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            try {
                if (parent != null) {
                    df.format(parent.tree, commit.tree)
                } else {
                    df.format(EmptyTreeIterator(), treeParser(repo, commit.tree))
                }
                df.close()
                return baosToString(baos)
            } finally {
                df.close()
            }
        }
    }

    // ── stage / unstage (host-native) ───────────────────────────────────────

    /** Stage or unstage one file. */
    fun setStaged(dir: File, path: String, staged: Boolean) {
        val git = open(dir) ?: return
        git.use { g ->
            if (staged) g.add().addFilepattern(path).call()
            else g.rm().addFilepattern(path).setCached(true).call()
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

    /** PersonIdent for new commits: sandbox config first, else a local default. */
    fun commitIdentity(dir: File, sandboxName: String?, sandboxEmail: String?): PersonIdent {
        val git = open(dir) ?: return PersonIdent(sandboxName ?: "Minis", sandboxEmail ?: "minis@local")
        git.use { g ->
            val cfg = g.repository.config
            val name = sandboxName ?: cfg.getString("user", null, "name") ?: "Minis"
            val email = sandboxEmail ?: cfg.getString("user", null, "email") ?: "minis@local"
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

    private fun countLines(bytes: ByteArray): Int =
        bytes.count { it == '\n'.code.toByte() } + if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) 1 else 0
}
