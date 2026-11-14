package com.openminis.app.ui.git

import android.util.Log
import com.openminis.app.git.GitCli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val GIT_STATUS_DEGRADE_TAG = "GitPanel"

/**
 * [T-git-panel-per-source-degrade] Every data source degrades INDEPENDENTLY:
 * a JGit status/numstat failure on the device must not paint "git status
 * failed" (or worse, a fake clean tree) while the sandbox CLI answers fine
 * seconds later. A broken native status tries sandbox once per refresh; a
 * sandbox failure surfaces as the read-failed notice instead of a lying
 * empty Changes tab.
 */
internal data class GitStatusDegradeResult(
    val status: GitCli.GitStatus?,
    val staged: Map<String, GitCli.NumStat>,
    val unstaged: Map<String, GitCli.NumStat>,
    val statusReadFailed: Boolean,
)

/**
 * Per-source degradation for the refresh trio (status + staged/unstaged
 * numstat). [nativeStatus] is the JGit result (null on thrown), the numstat
 * args are their native [Result]s — only sources that actually failed are
 * replaced by the sandbox fallback; a native success returning an empty map
 * (a truly clean tree) must not trigger a redundant sandbox round-trip.
 * [status] stays null only when BOTH sources failed — surfaced via
 * [statusReadFailed] as the read-failed notice.
 */
internal suspend fun degradeGitStatusPerSource(
    nativeStatus: GitCli.GitStatus?,
    stagedNative: Result<Map<String, GitCli.NumStat>>,
    unstagedNative: Result<Map<String, GitCli.NumStat>>,
    sessionId: String,
    root: String?,
): GitStatusDegradeResult {
    var status = nativeStatus
    var staged = stagedNative.getOrDefault(emptyMap())
    var unstaged = unstagedNative.getOrDefault(emptyMap())
    var statusReadFailed = false

    val nativeStatusBroken = status == null || !status.error.isNullOrBlank()
    val numstatBroken = stagedNative.isFailure || unstagedNative.isFailure
    if (!nativeStatusBroken && !numstatBroken) {
        return GitStatusDegradeResult(nativeStatus, staged, unstaged, false)
    }

    val sandbox = withContext(Dispatchers.IO) {
        runCatching {
            GitCli.status(sessionId, root) to (
                GitCli.numstat(sessionId, staged = true, root = root) to
                    GitCli.numstat(sessionId, staged = false, root = root)
                )
        }.onFailure {
            Log.w(
                GIT_STATUS_DEGRADE_TAG,
                "sandbox status fallback failed: ${it.javaClass.simpleName}: ${it.message?.take(160)}",
            )
        }.getOrNull()
    }
    if (sandbox != null && sandbox.first.error.isNullOrBlank()) {
        if (nativeStatusBroken) status = sandbox.first
        if (stagedNative.isFailure) staged = sandbox.second.first
        if (unstagedNative.isFailure) unstaged = sandbox.second.second
        Log.i(GIT_STATUS_DEGRADE_TAG, "status/numstat degraded to sandbox source")
    } else if (nativeStatusBroken) {
        statusReadFailed = true
    }
    return GitStatusDegradeResult(status, staged, unstaged, statusReadFailed)
}
