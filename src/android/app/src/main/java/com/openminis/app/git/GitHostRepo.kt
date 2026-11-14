package com.openminis.app.git

import android.util.Log
import com.openminis.app.sandbox.SessionWorkspace
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
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

    /** logcat tag for every swallowed-ref failure in the git panel stack. */
    private const val TAG = "GitPanel"

    /** Host directory of the session workspace (bind root of /var/minis/workspace). */
    fun workspaceDir(filesDir: File, sessionId: String): File =
        SessionWorkspace.hostDir(filesDir, sessionId, "workspace")

    /**
     * Open the repo at [dir]; null when the directory is not a git work tree.
     * HEAD is NOT checked: identity is independent of history — an empty repo
     * is a repo, and a broken-HEAD repo stays servable ([logPage] walks refs,
     * sandbox parity). Reads needing HEAD's tree fail there; [status] reports
     * the error. Closed on failure so open cannot leak a Repository.
     */
    internal fun open(dir: File): Git? = try {
        val g = Git.open(dir)
        runCatching { g.repository.repositoryState }.fold(
            onSuccess = { g },
            onFailure = { runCatching { g.close() }; null },
        )
    } catch (_: Exception) {
        null
    }

    /** Graph/checkout entry ([GitHostRepoGraph] owns the multi-ref walk). */
    internal fun openForGraph(dir: File): Git? = open(dir)

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
     * Identity is decoupled from history: an empty repo (zero commits) is a
     * repo (sandbox parity) and a broken HEAD stays servable via [logPage].
     */
    fun probe(dir: File): GitCli.GitRepoInfo {
        val git = open(dir) ?: return GitCli.GitRepoInfo(isRepo = false)
        git.use { g ->
            val repo = g.repository
            val branch = runCatching { repo.branch }.getOrDefault("")
            // [T-git-panel-probe-config] repo.config lazy-loads the stored
            // config (+ the user config chain); a parse hiccup here used to
            // escape as a raw exception and killed the whole native source
            // for that refresh — the only unguarded call in this block. Fold
            // it like the rest so a config problem degrades to missing
            // identity fields instead of a probe crash.
            val (userName, userEmail) = runCatching {
                val cfg = repo.config
                Pair(cfg.getString("user", null, "name"), cfg.getString("user", null, "email"))
            }.getOrDefault(Pair(null, null))
            val shallow = runCatching {
                java.io.File(repo.directory, "shallow").isFile ||
                    java.io.File(repo.commonDirectory, "shallow").isFile
            }.getOrDefault(false)
            return GitCli.GitRepoInfo(
                isRepo = true,
                root = dir.absolutePath,
                branch = branch,
                userName = userName,
                userEmail = userEmail,
                shallow = shallow,
            )
        }
    }

    // ── log ─────────────────────────────────────────────────────────────────

    /**
     * One history page, newest first, with parent hashes for the graph.
     * Delegates to [GitHostRepoGraph.logPage] — the ZCode-aligned multi-ref
     * walk (`--branches --tags --remotes --date-order --topo-order` shape)
     * with `%D` decorations; the old HEAD-only walk made every side branch
     * invisible to the History tab.
     */
    fun logPage(dir: File, offset: Int, limit: Int): List<GitCli.GitCommit> =
        GitHostRepoGraph.logPage(dir, offset, limit)

    // ── status ──────────────────────────────────────────────────────────────

    /** Working-tree status with ahead/behind, mapped onto [GitCli.GitStatus]. */
    fun status(dir: File): GitCli.GitStatus {
        val git = open(dir) ?: return GitCli.GitStatus(error = "not a git repository")
        git.use { g ->
            val repo = g.repository
            // Broken HEAD: JGit throws where real `git status` fails the same
            // way — carry it as GitStatus.error, the sandbox path's shape.
            val s = runCatching { g.status().call() }.getOrElse { e ->
                return GitCli.GitStatus(error = (e.message ?: "status failed").take(500))
            }
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

    /**
     * Ahead/behind vs the tracked upstream; (0,0,null) when none.
     *
     * [T-git-panel-shallow-guard] Two guards the old version lacked:
     * shallow clones (`.git/shallow` present — the graft boundary's missing
     * parents throw MissingObjectException mid-walk) skip the count
     * entirely, and ANY walk failure degrades to (0,0,upstream) instead of
     * poisoning the whole [status] into a false "clean tree" + error bar.
     */
    private fun aheadBehind(repo: Repository): Triple<Int, Int, String?> {
        val branch = repo.branch ?: return Triple(0, 0, null)
        val remote = repo.config.getString("branch", branch, "remote")
        val merge = repo.config.getString("branch", branch, "merge")
        val upstreamName = if (remote != null && merge != null) {
            "$remote/${merge.removePrefix("refs/heads/")}"
        } else null
        ?: return Triple(0, 0, null)
        repo.directory?.let { d -> if (File(d, "shallow").exists()) return Triple(0, 0, upstreamName) }
        val upstreamId = repo.resolve(upstreamName) ?: return Triple(0, 0, upstreamName)
        val headId = repo.resolve(Constants.HEAD) ?: return Triple(0, 0, upstreamName)
        return runCatching {
            val aheadWalk = RevWalk(repo).apply { isRetainBody = false }
            val behindWalk = RevWalk(repo).apply { isRetainBody = false }
            try {
                aheadWalk.markStart(aheadWalk.parseCommit(headId))
                aheadWalk.markUninteresting(aheadWalk.parseCommit(upstreamId))
                val ahead = aheadWalk.asSequence().count()
                behindWalk.markStart(behindWalk.parseCommit(upstreamId))
                behindWalk.markUninteresting(behindWalk.parseCommit(headId))
                val behind = behindWalk.asSequence().count()
                Triple(ahead, behind, upstreamName)
            } finally {
                aheadWalk.dispose()
                behindWalk.dispose()
            }
        }.getOrElse { Triple(0, 0, upstreamName) }
    }

    // ── branches ────────────────────────────────────────────────────────────

    /** Local + remote branches with the last commit subject per branch. */
    fun branches(dir: File): List<GitCli.GitBranchRef> {
        val git = open(dir) ?: return emptyList()
        git.use { g ->
            val repo = g.repository
            val locals = ArrayList<GitCli.GitBranchRef>()
            val remotes = ArrayList<GitCli.GitBranchRef>()
            // [T-git-panel-refs-degrade] branchList().call() runs the full
            // ListBranchCommand on top of RefDatabase enumeration; if THAT
            // enumeration misbehaves at runtime (some ART environments return
            // an empty listing where the JVM probe lists every ref), the
            // whole function used to explode into the caller's
            // runCatching→emptyList and the panel painted "no branches" over
            // a healthy repo. Enumerate through the RefDatabase directly —
            // same data, one layer less to fail — and keep the command as a
            // fallback for THROWN failures only; an empty-but-clean result
            // rides the ViewModel's sandbox degrade instead (the command
            // layer would only return the same empty listing).
            val branchList = runCatching {
                repo.refDatabase.getRefsByPrefix("refs/heads/")
            }.recoverCatching {
                g.branchList().call().map { it }
            }.onFailure {
                Log.w(TAG, "branch enumeration failed: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(emptyList())
            val walk = RevWalk(repo)
            walk.isRetainBody = false
            try {
                for (ref in branchList) {
                    val commit = try {
                        walk.parseCommit(ref.objectId).shortMessage
                    } catch (_: Exception) {
                        ""
                    }
                    val short = ref.name.removePrefix("refs/heads/")
                    locals += GitCli.GitBranchRef(
                        name = short,
                        isCurrent = ref.name == repo.fullBranch,
                        lastSubject = commit,
                        // Tracking short name from branch.<n>.remote/merge —
                        // the for-each-ref %(upstream:short) equivalent.
                        upstream = upstreamShort(repo, short),
                    )
                }
                // Remote-tracking branches, newest commit first (the sandbox
                // sorts by -committerdate desc; packed-refs order is arbitrary),
                // capped to keep the switcher readable.
                val remoteRefs = runCatching {
                    repo.refDatabase.getRefsByPrefix("refs/remotes/origin/")
                }.onFailure {
                    Log.w(TAG, "remote-ref enumeration failed: ${it.javaClass.simpleName}: ${it.message}")
                }.getOrDefault(emptyList())
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
                    remotes += GitCli.GitBranchRef(
                        name = "origin/$short",
                        isCurrent = false,
                        lastSubject = commit,
                    )
                }
            } finally {
                walk.dispose()
            }
            // [T-git-panel-zcode-order] Group by REF SOURCE, not by slash:
            // a local "feature/x" branch contains '/' but must stay in the
            // local group (the old name-based partition demoted it to the
            // remote section). Current branch first, then alphabetical;
            // remotes keep their recency order behind the locals.
            val sortedLocals = locals.sortedWith(
                compareByDescending<GitCli.GitBranchRef> { it.isCurrent }.thenBy { it.name },
            )
            return (sortedLocals + remotes).distinctBy { it.name }
        }
    }

    // ── stashes ─────────────────────────────────────────────────────────────

    /** `branch.<n>.remote` + `branch.<n>.merge` → `origin/main` short form. */
    private fun upstreamShort(repo: Repository, branch: String): String? {
        val remote = repo.config.getString("branch", branch, "remote") ?: return null
        val merge = repo.config.getString("branch", branch, "merge") ?: return null
        return "$remote/${merge.removePrefix("refs/heads/")}"
    }

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
     * Host-side repo discovery: workspace itself + repos nested up to four
     * levels below. Delegates to [GitHostRepoDiscovery] — the ZCode-aligned
     * walk that covers the SAME set as the sandbox DISCOVER_CMD (the old
     * two-level host walk vs mindepth-2 sandbox find disagreed, flipping the
     * panel between its fast and slow paths).
     */
    fun locate(workspaceDir: File): Pair<File, String>? =
        GitHostRepoDiscovery.locate(workspaceDir)

    /** Guest-side absolute path of a repo discovered under the workspace. */
    fun guestPath(relative: String): String = GitHostRepoDiscovery.guestPath(relative)

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
}
