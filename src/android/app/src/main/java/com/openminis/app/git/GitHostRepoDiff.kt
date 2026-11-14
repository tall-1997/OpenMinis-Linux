package com.openminis.app.git

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * [T-git-panel-diff-extract] Diff/numstat computation for the git panel,
 * split out of [GitHostRepo] when the file outgrew its architecture-policy
 * line cap (the diff family was ~half of it and is a self-contained job:
 * index-vs-worktree numstat, per-file and per-commit diffs, rename-aware).
 * Opens repos through the same [GitHostRepo.open] the read family uses.
 *
 * All methods are synchronous — callers wrap in coroutines.
 */
object GitHostRepoDiff {

    // ── numstat (staged / unstaged) ─────────────────────────────────────────

    /**
     * Numstat rows for the staged diff (HEAD vs index), keyed by path.
     * The index side is a [DirCacheIterator] — `dc.writeTree(inserter)` would
     * write a fresh loose tree object into .git/objects on EVERY refresh,
     * polluting the user's repository object database with garbage.
     */
    fun numstatStaged(dir: File): Map<String, GitCli.NumStat> {
        val git = GitHostRepo.open(dir) ?: return emptyMap()
        git.use { g ->
            val repo = g.repository
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            // [T-git-panel-rename-detect] Sandbox `git diff` detects renames
            // by default; without the matching JGit switch a renamed file
            // rendered as one deleted + one added row.
            df.setDetectRenames(true)
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
        val git = GitHostRepo.open(dir) ?: return emptyMap()
        git.use { g ->
            val repo = g.repository
            val baos = ByteArrayOutputStream()
            val df = DiffFormatter(baos)
            df.setRepository(repo)
            // Same rename parity as the staged side (see numstatStaged).
            df.setDetectRenames(true)
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
        val git = GitHostRepo.open(dir) ?: return ""
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
        val git = GitHostRepo.open(dir) ?: return null
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
        val git = GitHostRepo.open(dir) ?: return emptyList()
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
        val git = GitHostRepo.open(dir) ?: return "(diff unavailable)"
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
        val git = GitHostRepo.open(dir) ?: return "(diff unavailable)"
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
        val git = GitHostRepo.open(dir) ?: return "(diff unavailable)"
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
