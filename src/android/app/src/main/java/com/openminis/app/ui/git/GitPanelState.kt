package com.openminis.app.ui.git

import com.openminis.app.git.GitCli.CommitFileStat
import com.openminis.app.git.GitCli.GitBranchRef
import com.openminis.app.git.GitCli.GitCommit
import com.openminis.app.git.GitCli.GitCommitDetail
import com.openminis.app.git.GitCli.GitRepoInfo
import com.openminis.app.git.GitCli.GitStashEntry
import com.openminis.app.git.GitCli.GitStatus
import com.openminis.app.git.GitCli.NumStat

/** Everything the panel renders, in one immutable snapshot. */
data class GitPanelState(
    val loading: Boolean = true,
    val repo: GitRepoInfo? = null,
    val status: GitStatus? = null,
    val log: List<GitCommit> = emptyList(),
    /** Older commits exist beyond the loaded window (history pagination). */
    val logHasMore: Boolean = true,
    /** A load-more call is in flight — the footer spinner shows. */
    val logLoadingMore: Boolean = false,
    /** First remote's push URL, display form (null when the repo has none). */
    val remote: String? = null,
    /** Local + remote branches for the header switcher. */
    val branches: List<GitBranchRef> = emptyList(),
    /** Stash entries for the Changes tab. */
    val stashes: List<GitStashEntry> = emptyList(),
    /** Most recent commit for the header card (null before the first commit). */
    val lastCommit: GitCommit? = null,
    /** `git diff --cached --numstat`, keyed by path. */
    val stagedNumstat: Map<String, NumStat> = emptyMap(),
    /** `git diff --numstat`, keyed by path. */
    val unstagedNumstat: Map<String, NumStat> = emptyMap(),
    val tab: GitPanelTab = GitPanelTab.Changes,
    /** A mutating git call (stage/commit) is in flight — buttons dim. */
    val busy: Boolean = false,
    /** The AI commit-message call is in flight. */
    val generating: Boolean = false,
    val draftMessage: String = "",
    /** Transient one-line result (commit ok, error text…). Cleared on refresh. */
    val notice: String? = null,
    val diff: GitDiffView? = null,
    /** History tab: local filter over the loaded window. */
    val searchQuery: String = "",
    /** Branch switcher: local filter over the branch list (ZCode parity). */
    val branchSearch: String = "",
    /** Commit-detail bottom sheet; null when closed. */
    val commitSheet: GitCommitSheet? = null,
)

data class GitDiffView(
    val title: String,
    val loading: Boolean,
    val content: String,
)

enum class GitPanelTab { Changes, History }

data class GitCommitSheet(
    val hash: String,
    val detail: GitCommitDetail? = null,
    val files: List<CommitFileStat> = emptyList(),
    /** Set only when git show/numstat failed — the sheet shows the message. */
    val error: String? = null,
)

/** Aggregated line counts for the stats bar above the file list. */
data class FileStatTotals(
    val files: Int = 0,
    val added: Int = 0,
    val deleted: Int = 0,
)

/**
 * Stats-bar source of truth: the row count comes from the status list, the
 * +/- lines from per-entry numstat lookups (staged rows read the staged map,
 * unstaged rows the unstaged map). Untracked and binary rows contribute 0.
 */
fun changesTotals(
    status: GitStatus?,
    staged: Map<String, NumStat>,
    unstaged: Map<String, NumStat>,
): FileStatTotals {
    val entries = status?.entries.orEmpty()
    var added = 0
    var deleted = 0
    for (e in entries) {
        val stat = if (e.staged) staged[e.path] else unstaged[e.path]
        if (stat != null && !stat.binary) {
            added += stat.added
            deleted += stat.deleted
        }
    }
    return FileStatTotals(entries.size, added, deleted)
}

/**
 * Header-card display form of a remote URL:
 * `https://github.com/tall-1997/OpenMinis-Linux.git` and
 * `git@github.com:tall-1997/OpenMinis-Linux.git` both become
 * `github.com/tall-1997/OpenMinis-Linux`. Null for empty input.
 */
fun displayRemote(url: String?): String? {
    val raw = url?.trim().orEmpty()
    if (raw.isBlank()) return null
    val noScheme = raw.substringAfter("://")
    val noUser = noScheme.substringAfterLast('@')
    val host = noUser.substringBefore(':')
    val rest = noUser.substringAfter(':', "")
    val body = if (rest.isNotEmpty()) "$host/$rest" else host
    return body.removeSuffix("/").removeSuffix(".git").ifBlank { null }
}

/** One row of the rendered commit graph. */
data class CommitLaneRow(
    val commit: GitCommit,
    /** Column where this commit's node sits. */
    val lane: Int,
    /** All columns carrying a rail through this row, ascending. */
    val activeLanes: List<Int>,
    /** More than one parent lives in this window — draw the merge ring. */
    val isMerge: Boolean,
    /** At least two commits in this window descend from it — draw the fork diamond. */
    val isFork: Boolean = false,
)

/**
 * Deterministic lane layout for a newest-first commit window, two passes:
 *
 * 1. Lane assignment: the first parent inherits its child's lane (linear
 *    rails stay straight); every other parent reserves a fresh lane (the
 *    curve a merge or side branch draws); roots get fresh lanes. Promises are
 *    made with `putIfAbsent`, so a commit referenced by several children
 *    keeps its first promise — deterministic regardless of log order.
 * 2. Rails: a first-parent rail spans row(child)..row(parent) at the child's
 *    lane; each merge connector spans row(merge)..row(other parent) at that
 *    parent's lane. A row's [CommitLaneRow.activeLanes] is the set of rails
 *    crossing it — vertical lines draw first, nodes sit on top.
 *
 * O(n²) worst case on the window size; the window is ≤100 rows by design.
 */
fun graphLayout(commits: List<GitCommit>): List<CommitLaneRow> {
    if (commits.isEmpty()) return emptyList()
    val hashes = commits.mapTo(HashSet()) { it.hash }
    val indexOf = commits.withIndex().associate { it.value.hash to it.index }

    // Fork points: commits with ≥2 children inside the window.
    val childrenOf = HashMap<String, Int>()
    for (c in commits) {
        for (p in c.parents) {
            if (p in hashes) childrenOf[p] = (childrenOf[p] ?: 0) + 1
        }
    }

    val laneOf = HashMap<String, Int>()
    var nextLane = 0
    for (c in commits) {
        val ps = c.parents.filter { it in hashes }
        if (ps.isEmpty()) {
            laneOf.getOrPut(c.hash) { nextLane++ }
        } else {
            val own = laneOf.getOrPut(c.hash) { nextLane++ }
            ps.forEachIndexed { k, p ->
                if (k == 0) laneOf.putIfAbsent(p, own)
                else laneOf.putIfAbsent(p, nextLane++)
            }
        }
    }

    data class Rail(val lane: Int, val from: Int, val to: Int)

    val rails = ArrayList<Rail>(commits.size * 2)
    for (c in commits) {
        val i = indexOf.getValue(c.hash)
        val lane = laneOf.getValue(c.hash)
        val ps = c.parents.filter { it in hashes }
        val end = if (ps.isEmpty()) commits.lastIndex else indexOf.getValue(ps[0])
        rails += Rail(lane, i, end)
        for (k in 1 until ps.size) {
            rails += Rail(laneOf.getValue(ps[k]), i, indexOf.getValue(ps[k]))
        }
    }

    return commits.mapIndexed { j, c ->
        val active = rails.asSequence()
            .filter { it.from <= j && j <= it.to }
            .map { it.lane }
            .toSortedSet()
            .toList()
        CommitLaneRow(
            commit = c,
            lane = laneOf.getValue(c.hash),
            activeLanes = active,
            isMerge = c.parents.count { it in hashes } > 1,
            isFork = (childrenOf[c.hash] ?: 0) >= 2,
        )
    }
}

/**
 * Local history filter: case-insensitive match on subject, author or hash.
 * Empty query returns the window untouched.
 */
fun filterHistory(log: List<GitCommit>, query: String): List<GitCommit> {
    val q = query.trim()
    if (q.isEmpty()) return log
    return log.filter {
        it.subject.contains(q, ignoreCase = true) ||
            it.author.contains(q, ignoreCase = true) ||
            it.hash.contains(q, ignoreCase = true) ||
            it.shortHash.contains(q, ignoreCase = true)
    }
}
