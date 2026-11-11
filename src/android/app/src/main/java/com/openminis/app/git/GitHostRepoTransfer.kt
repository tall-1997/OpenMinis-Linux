package com.openminis.app.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * [T-git-panel-native-push] Host-native push/pull for the Git panel — the
 * last two operations that still paid the PRoot shell round-trip (sandbox
 * boot + shell + git) on every tap. The workspace is the same directory the
 * rest of the panel reads via JGit, so transfer now gets the same instant
 * feel.
 *
 * CREDENTIALS: the sandbox shell path authenticates with the git config
 * inside PRoot (embedded credentials in the remote URL / credential
 * helper). JGit on the host cannot see those, so the native path serves
 * remotes whose URL embeds credentials (`https://user:pass@…`, the form a
 * stored-credentials helper writes) or needs none (a local/bare path).
 * Anything else — clean https, ssh — returns [Result.Fallback] and the
 * ViewModel transparently falls back to `GitCli`, identical behaviour,
 * merely slower. This keeps the panel honest: no half-authenticated
 * attempts against a remote we know we cannot authenticate to.
 *
 * PULL SEMANTICS: ff-only, mirroring `GitCli.pull`. Implemented as an
 * explicit fetch + fast-forward check rather than `PullCommand` so a
 * diverged upstream is a clean [Result.Error] ("diverged — commit or reset
 * first") instead of JGit's silent no-op or an unexpected merge commit.
 *
 * All methods are synchronous — the ViewModel wraps them in coroutines.
 */
object GitHostRepoTransfer {

    /** Outcome of a native transfer attempt. */
    sealed interface Result {
        /**
         * The native path cannot serve this repo (not a repo on the host, no
         * `origin` remote, or remote credentials not visible to JGit) — the
         * caller falls back to the sandbox shell path unchanged.
         */
        data object Fallback : Result

        /** Native success. [message] null → caller shows the plain "pushed"/"pulled". */
        data class Done(val message: String? = null) : Result

        /** The native attempt ran but failed; [message] is shown verbatim. */
        data class Error(val message: String) : Result
    }

    // ── credentials ─────────────────────────────────────────────────────────

    /** How the native path can reach `origin`. */
    internal sealed interface Access {
        /** Local/bare path remote — JGit's local transport needs no credentials. */
        data object NativeNoCredentials : Access

        /** http(s) remote with credentials embedded in the URL. */
        data class Native(val provider: CredentialsProvider) : Access

        /** Clean https, ssh, or no remote at all — the sandbox path owns these. */
        data object Fallback : Access
    }

    /**
     * The token-only form (`https://TOKEN@host/…`, GitHub accepts it with an
     * empty password) maps to the same provider with an empty password.
     */
    internal fun embeddedProvider(url: String): CredentialsProvider? {
        val m = Regex("^https?://([^/@:]+)(?::([^@]*))?@").find(url) ?: return null
        val user = m.groupValues[1]
        if (user.isEmpty()) return null
        return UsernamePasswordCredentialsProvider(user, m.groupValues[2])
    }

    /**
     * Classify the remote URL: http(s) with embedded credentials →
     * [Access.Native]; http(s) without → [Access.Fallback] (no
     * half-authenticated attempts against a remote we cannot serve);
     * local paths and `file://` → [Access.NativeNoCredentials]; every other
     * scheme (`ssh://`, `git://`) needs a key store the host side does not
     * have → [Access.Fallback]; no `origin` configured → [Access.Fallback].
     */
    internal fun resolveAccess(url: String?): Access = when {
        url == null -> Access.Fallback
        !url.contains("://") -> Access.NativeNoCredentials // bare local path
        url.startsWith("file://") -> Access.NativeNoCredentials
        url.startsWith("http://") || url.startsWith("https://") ->
            embeddedProvider(url)?.let { Access.Native(it) } ?: Access.Fallback
        else -> Access.Fallback
    }

    // ── push ────────────────────────────────────────────────────────────────

    /**
     * Push the current branch to `origin`. Rejections map to [Result.Error]
     * with the git-native wording ("non-fast-forward — pull first"); a push
     * that found nothing to send reports "up to date".
     */
    fun push(dir: File): Result {
        val g = openOrFallback(dir) ?: return Result.Fallback
        return try {
            when (val access = resolveAccess(originUrl(g))) {
                Access.Fallback -> Result.Fallback
                Access.NativeNoCredentials ->
                    summarizePush(g.push().setRemote("origin").call())
                is Access.Native ->
                    summarizePush(
                        g.push().setRemote("origin").setCredentialsProvider(access.provider).call(),
                    )
            }
        } catch (e: TransportException) {
            Result.Error(e.message ?: "push failed: network error")
        } catch (e: Exception) {
            Result.Error(e.message ?: "push failed")
        } finally {
            runCatching { g.close() }
        }
    }

    private fun summarizePush(results: Iterable<org.eclipse.jgit.transport.PushResult>): Result {
        var sent = false
        for (r in results) {
            for (u in r.remoteUpdates) {
                when (u.status) {
                    RemoteRefUpdate.Status.OK -> sent = true
                    RemoteRefUpdate.Status.UP_TO_DATE -> {} // no-op: nothing to send on this ref
                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                        return Result.Error("push rejected: non-fast-forward — pull first")
                    else ->
                        return Result.Error(
                            "push rejected: " + (u.message?.takeIf { it.isNotBlank() } ?: u.status.name.lowercase()),
                        )
                }
            }
        }
        return Result.Done(if (sent) null else "up to date")
    }

    // ── pull (ff-only) ──────────────────────────────────────────────────────

    /**
     * Fetch `origin` and fast-forward the current branch only. A diverged
     * upstream refuses with the same meaning as the sandbox `git pull
     * --ff-only` — no merge commit is ever created here.
     */
    fun pull(dir: File): Result {
        val g = openOrFallback(dir) ?: return Result.Fallback
        return try {
            when (val access = resolveAccess(originUrl(g))) {
                Access.Fallback -> return Result.Fallback
                Access.NativeNoCredentials -> pullFfOnly(g, fetch = { it.call() })
                is Access.Native ->
                    pullFfOnly(g, fetch = { it.setCredentialsProvider(access.provider).call() })
            }
        } catch (e: TransportException) {
            Result.Error(e.message ?: "pull failed: network error")
        } catch (e: Exception) {
            Result.Error(e.message ?: "pull failed")
        } finally {
            runCatching { g.close() }
        }
    }

    /**
     * The ff-only body after a successful fetch: resolve the upstream ref for
     * the current branch, refuse to move when diverged, fast-forward
     * otherwise. [fetch] has already applied credentials where the remote
     * needs them.
     */
    private fun pullFfOnly(g: Git, fetch: (org.eclipse.jgit.api.FetchCommand) -> FetchResult): Result {
        fetch(g.fetch().setRemote("origin"))
        val head = g.repository.resolve(Constants.HEAD) ?: return Result.Error("pull failed: unborn HEAD")
        val branch = g.repository.branch
            ?: return Result.Error("pull failed: detached HEAD — checkout a branch first")
        val remote = g.repository.resolve("refs/remotes/origin/$branch")
            ?: return Result.Done("no upstream for $branch")
        if (remote == head) return Result.Done("up to date")
        RevWalk(g.repository).use { rw ->
            val headCommit = rw.parseCommit(head)
            val remoteCommit = rw.parseCommit(remote)
            if (!rw.isMergedInto(headCommit, remoteCommit)) {
                return Result.Error("pull refused: diverged from origin/$branch — commit or reset first")
            }
        }
        g.merge().include(remote).setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
        return Result.Done(null)
    }

    // ── shared plumbing ─────────────────────────────────────────────────────

    /**
     * Open the repo at [dir]; null (→ [Result.Fallback]) when the host
     * directory is not a work tree — the sandbox path is the honest owner of
     * that state, it may still see a repo the host side cannot.
     */
    private fun openOrFallback(dir: File): Git? = try {
        Git.open(dir)
    } catch (_: RepositoryNotFoundException) {
        null
    }

    /** The `origin` URL, null when the remote is not configured. */
    private fun originUrl(g: Git): String? =
        g.repository.config.getString("remote", "origin", "url")
}
