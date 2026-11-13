package com.openminis.app.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk

/**
 * [T-git-panel-zcode-graph] Host-native commit graph, aligned with ZCode's
 * `getCommitGraph` contract:
 *
 * ```
 * git log HEAD --branches --tags --remotes --date-order --topo-order
 *          --skip=N --max-count=M+1 --format=%H %P %an %at %s %D
 * ```
 *
 * The old [GitHostRepo.logPage] walked HEAD only — every side branch was
 * invisible to the History tab, and there was no decoration data, so the
 * graph could not show HEAD/`main`/`origin/main` badges at all. The walk
 * here marks EVERY ref (heads + remotes + tags, the `--branches --tags
 * --remotes` set) and sorts TOPO + COMMIT_TIME_DESC, which is exactly the
 * `--date-order --topo-order` pair: parent-before-child is guaranteed and
 * same-row commits interleave by time, so offset pagination stays stable
 * across refreshes.
 *
 * Decorations come from a sha→refs map built over the same ref database —
 * the `%D` column — rendered by the UI as the HEAD/main/origin/main badges.
 */
object GitHostRepoGraph {

    /**
     * One history page over all visible refs, newest first, with parent
     * hashes AND ref decorations for the graph UI. Mapped onto
     * [GitCli.GitCommit] so the ViewModel/UI stay source-agnostic.
     */
    fun logPage(dir: java.io.File, offset: Int, limit: Int): List<GitCli.GitCommit> {
        val git = GitHostRepo.openForGraph(dir) ?: return emptyList()
        git.use { g ->
            val repo = g.repository
            val decorations = decorationMap(repo)
            val walk = RevWalk(repo)
            // Bodies are needed ONLY for the ≤limit commits actually returned:
            // retain-body would buffer EVERY reachable commit's body through
            // the whole skip walk (a 10k-commit repo buffered 10k bodies per
            // refresh), and the FileChangeHub watcher re-runs this every 2s.
            walk.isRetainBody = false
            // JGit's TOPO + COMMIT_TIME_DESC pair is the equivalent of git's
            // `--date-order` (topo containment comes with it); the sandbox
            // command passes `--date-order --topo-order`, whose later flag
            // overrides the sort to graph order — same invariants
            // (parent-before-child, deterministic), tie order may differ
            // across the two sources for same-timestamp siblings.
            walk.sort(RevSort.TOPO)
            walk.sort(RevSort.COMMIT_TIME_DESC, true)
            try {
                var marked = 0
                runCatching {
                    val head = repo.resolve(Constants.HEAD)
                    if (head != null) {
                        walk.markStart(walk.parseCommit(head))
                        marked++
                    }
                }
                for (ref in repo.refDatabase.getRefs()) {
                    // The sandbox contract is `HEAD --branches --tags
                    // --remotes`: getRefs() also returns refs/stash,
                    // refs/notes, refs/original — walking those painted
                    // stash commits (and their exclusive ancestors) into the
                    // History tab and diverged the two sources.
                    val name = ref.name
                    val anchorable = name.startsWith("refs/heads/") ||
                        name.startsWith("refs/remotes/") ||
                        name.startsWith("refs/tags/")
                    if (!anchorable) continue
                    val id = ref.objectId ?: continue
                    if (id == org.eclipse.jgit.lib.ObjectId.zeroId()) continue
                    runCatching {
                        walk.markStart(walk.parseCommit(id))
                        marked++
                    }
                }
                if (marked == 0) return emptyList()
                val commits = ArrayList<GitCli.GitCommit>(limit)
                var skipped = 0
                for (rev in walk) {
                    if (skipped < offset) {
                        skipped++
                        continue
                    }
                    if (commits.size >= limit) break
                    // Headers-only walk: the body (subject) is parsed lazily,
                    // just for the rows that leave the function.
                    walk.parseBody(rev)
                    commits += GitCli.GitCommit(
                        hash = rev.name,
                        shortHash = rev.abbreviate(8).name(),
                        author = rev.authorIdent.name,
                        epochSeconds = rev.commitTime.toLong(),
                        subject = rev.shortMessage,
                        parents = rev.parents.map { it.name },
                        refs = decorations[rev.name].orEmpty(),
                    )
                }
                return commits
            } finally {
                walk.dispose()
            }
        }
    }

    /**
     * `%D` equivalent: sha → short ref names. HEAD pins its target branch
     * (rendered with the HEAD badge); heads carry their short name;
     * remotes carry `origin/<name>`; tags carry their short name. Sorted
     * HEAD-first then alphabetically for stable rendering.
     */
    private fun decorationMap(repo: Repository): Map<String, List<String>> {
        val map = HashMap<String, MutableList<String>>()
        fun add(sha: String, label: String) {
            map.getOrPut(sha) { mutableListOf() }.add(label)
        }
        // resolve(HEAD) works in BOTH modes: symbolic → branch tip sha,
        // detached → the pinned commit sha (exactRef(HEAD).target is null
        // when detached, which dropped the HEAD badge entirely).
        val headTarget = runCatching { repo.resolve(Constants.HEAD)?.name }.getOrNull()
        val branchTips = HashSet<String>()
        for (ref in repo.refDatabase.getRefs()) {
            val id = ref.objectId?.name ?: continue
            val name = ref.name
            when {
                name == Constants.HEAD -> continue
                name.startsWith("refs/heads/") -> {
                    val short = name.removePrefix("refs/heads/")
                    branchTips.add(id)
                    add(id, short)
                    if (id == headTarget) add(id, "HEAD")
                }
                name.startsWith("refs/remotes/") -> {
                    val short = name.removePrefix("refs/remotes/")
                    if (short.endsWith("/HEAD")) continue // origin/HEAD alias
                    add(id, short)
                }
                name.startsWith("refs/tags/") -> {
                    // [T-git-panel-annotated-tag] An annotated tag's objectId
                    // is the TAG object's sha; the walk rows carry the PEELED
                    // commit's sha. Without peeling, annotated tags never
                    // decorated anything while lightweight tags did.
                    val peeled = runCatching { repo.refDatabase.peel(ref) }.getOrNull() ?: ref
                    val sha = (peeled.peeledObjectId ?: peeled.objectId)?.name ?: continue
                    add(sha, name.removePrefix("refs/tags/"))
                }
            }
        }
        // Detached HEAD: no branch carries the badge — stamp it directly.
        if (headTarget != null && headTarget !in branchTips) add(headTarget, "HEAD")
        for (list in map.values) {
            // remove(element) returns a BOOLEAN (whether anything was
            // removed) — the old `val head = list.remove("HEAD"); if (head
            // != null)` compiled but stamped a phantom HEAD badge onto every
            // decorated commit.
            val hadHead = list.remove("HEAD")
            list.sort()
            if (hadHead) list.add(0, "HEAD")
        }
        return map
    }

    /**
     * Check out a branch with ZCode's `switchBranch` pre-validation:
     * refuse while a merge/rebase/cherry-pick is in progress
     * ([Repository.state] covers what git refuses the same way) and refuse
     * with unresolved conflicts. [create] makes a NEW branch at HEAD first
     * (`checkout -b`); without it, `origin/<name>` targets create the local
     * tracking branch — the remote-tracking namespace itself is not a legal
     * checkout target.
     *
     * @return error text, or null on success (the caller reloads).
     */
    fun checkout(dir: java.io.File, name: String, create: Boolean = false): String? {
        val git = GitHostRepo.openForGraph(dir) ?: return "not a git repository"
        git.use { g ->
            val repo = g.repository
            val state = runCatching { repo.repositoryState }.getOrElse { e ->
                return e.message ?: "repository state unavailable"
            }
            if (state != RepositoryState.SAFE) {
                return "another git operation is in progress ($state)"
            }
            val conflicting = runCatching { g.status().call().conflicting }.getOrElse { e ->
                return e.message ?: "status failed"
            }
            if (conflicting.isNotEmpty()) {
                return "unresolved conflicts: ${conflicting.take(3).joinToString()}"
            }
            val command = if (create) {
                // Creating a branch named like a ref namespace would produce
                // an uncheckoutable ref — refuse with git's own tone.
                if (name.contains("refs/") || name.contains("//")) {
                    return "invalid branch name: $name"
                }
                g.checkout().setCreateBranch(true).setName(name)
            } else if (runCatching { repo.exactRef("refs/heads/$name") != null }.getOrDefault(false)) {
                // LOCAL FIRST: a local branch — slashed or not — is always
                // checked out by name. The second-round review caught the
                // residual: with the remote check FIRST, a repo that also
                // had a literal remote named "feature" re-routed the local
                // feature/x into a phantom "x" tracking branch.
                g.checkout().setName(name)
            } else if (name.contains('/') &&
                runCatching { repo.exactRef("refs/remotes/$name") != null }.getOrDefault(false)
            ) {
                // Remote ref: create the local tracking branch named after
                // its short name (origin/main → main), like the sandbox
                // `checkout -b <local> --track <remote>` path.
                val local = name.substringAfter('/')
                if (runCatching { repo.exactRef("refs/heads/$local") != null }.getOrDefault(false)) {
                    g.checkout().setName(local)
                } else {
                    g.checkout().setCreateBranch(true).setName(local).setStartPoint(name)
                }
            } else {
                g.checkout().setName(name)
            }
            return try {
                command.call()
                null
            } catch (e: Exception) {
                e.message ?: "checkout failed"
            }
        }
    }
}
