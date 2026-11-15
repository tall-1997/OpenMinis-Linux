package com.openminis.app.ui.git

import com.openminis.app.git.GitCli
import com.openminis.app.git.GitHostRepo
import com.openminis.app.git.GitHostRepoGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Branch mutation entry points for [GitPanelViewModel], extracted to keep the
 * VM under its architecture ceiling. Same package — the Compose call sites in
 * GitPanelScreen resolve these extensions without imports.
 */

/**
 * Check out [name] with ZCode's pre-validation, host-first:
 * JGit performs the checkout against the workspace's own `.git` (no
 * PRoot round-trip — the old sandbox-only path reported "shell
 * unavailable" whenever the sandbox wasn't booted, leaving the panel
 * "visible but inert"); the sandbox path remains the fallback. A
 * remote ref creates the local tracking branch named after its short
 * name (`origin/main` → `main`). Creating a local branch literally
 * named "origin/main" would collide with the remote-tracking
 * namespace, so the short name is the only legal target.
 */
internal fun GitPanelViewModel.switchBranch(name: String) {
    mutate {
        val dir = hostDir
        if (dir != null) {
            val hostError = withContext(Dispatchers.IO) { GitHostRepoGraph.checkout(dir, name) }
            if (hostError == null) return@mutate null
            // Host refused (in-progress operation / conflicts) is a real
            // refusal, not a transport failure — surface it verbatim.
            if (!hostError.contains("not a git repository")) return@mutate hostError
        }
        // [T-git-sandbox-checkout-route] Mirror the JGit route: check the
        // LOCAL branch list (any branch the panel knows, slashed or not)
        // before treating a slashed name as a remote-tracking ref. The old
        // !contains('/') test misrouted a LOCAL feature/x into
        // checkout -b feature/x → git refuses "already exists".
        // [T-git-branch-is-remote] "Local" means NOT a remote-TRACKING
        // ref — the switcher list mixes both, and name matching alone
        // saw `origin/main` as "local exists" → `git checkout origin/main`
        // → detached HEAD instead of creating the tracking branch.
        val localExists = _state.value.branches.any { it.name == name && !it.isRemote }
        if (localExists || !name.contains('/')) {
            GitCli.execSimple(sessionId, "checkout", name, root = guestRoot)
        } else {
            val local = name.substringAfter('/')
            GitCli.execSimple(sessionId, "checkout", "-b", local, "--track", name, root = guestRoot)
        }
    }
}

/**
 * Create a new branch at HEAD and check it out (ZCode's
 * `createBranchAndSwitch`). Empty/whitespace names are refused; an
 * existing branch name checks out in place instead of failing.
 */
internal fun GitPanelViewModel.createBranchAndCheckout(name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || _state.value.busy) return
    mutate {
        val dir = hostDir
        if (dir != null) {
            // [T-git-branch-is-remote] "Exists locally" = a non-remote ref with
            // this name. The old !contains('/') test treated a LOCAL feature/x
            // as non-existent → checkout create=true failed "already exists".
            val exists = withContext(Dispatchers.IO) {
                runCatching { GitHostRepo.branches(dir).any { it.name == trimmed && !it.isRemote } }
                    .getOrDefault(false)
            }
            val err = withContext(Dispatchers.IO) { GitHostRepoGraph.checkout(dir, trimmed, create = !exists) }
            if (err == null) return@mutate null
        }
        GitCli.execSimple(sessionId, "checkout", "-b", trimmed, root = guestRoot)
    }
}
