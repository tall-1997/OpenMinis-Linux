package com.openminis.app.ui.git

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.git.GitCli
import com.openminis.app.git.GitCli.CommitOutcome
import com.openminis.app.git.GitHostRepoTransfer
import com.openminis.app.git.GitCli.GitCommit
import com.openminis.app.git.GitCli.GitFileEntry
import com.openminis.app.git.GitCommitMessageGenerator
import com.openminis.app.git.GitHostRepoGraph
import com.openminis.app.git.GitCli.GitRepoInfo
import com.openminis.app.git.GitCli.GitStatus
import com.openminis.app.git.GitHostRepo
import com.openminis.app.sandbox.FileChangeHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-git-panel-dual-source] Dual-source git panel: every read goes to
 * [GitHostRepo] first (JGit against the host workspace — instant, no shell),
 * and falls back to the `GitCli` sandbox path only when the native probe
 * fails. The sandbox stays authoritative for push/pull (credentials live in
 * its git config) and for checkout, so `repo.root` is always the GUEST path
 * even when the data came from the host side.
 */
class GitPanelViewModel(
    application: Application,
    private val sessionId: String,
    providerRepository: ProviderRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(GitPanelState())
    val state: StateFlow<GitPanelState> = _state.asStateFlow()

    private val app = application

    private val messageGenerator = GitCommitMessageGenerator(app, providerRepository)

    private var fileWatchJob: Job? = null
    private var lastAutoRefreshMs = 0L

    /**
     * [T-git-panel-refs-degrade] Sticky degrade flag: when the native (JGit)
     * ref reads return EMPTY on a repo whose HEAD resolves (the probe
     * reported a branch name), the native source is treating a readable repo
     * as an empty one — and every 2s auto-refresh would repeat the same
     * failure. Once the sandbox CLI has proven it CAN read the refs, stay on
     * the sandbox for log/branches until the panel is re-entered (a new
     * ViewModel resets the flag and retries native). Status/numstat/remote
     * keep riding native: they demonstrably succeed where ref enumeration
     * fails.
     */
    private var preferSandboxRefs = false

    /**
     * [T-git-panel-single-flight] One refresh at a time: init, the FileChangeHub
     * watcher and manual taps can all fire concurrently, and two interleaved
     * refreshes race their final `_state.update` — the older full-state write
     * lands last and wipes fresher data. A new refresh cancels the previous one.
     */
    private var refreshJob: Job? = null

    /** Native repo root on the host filesystem; null = sandbox path only. */
    private var hostDir: File? = null

    /** Guest path of [hostDir] — what sandbox commands `-C` into. */
    private var guestRoot: String? = null

    init {
        refresh()
        observeWorkspaceChanges()
    }

    /**
     * [T-file-hub] Live refresh: FileChangeHub watches the session workspace
     * on the HOST side of the PRoot bind (guest writes land on the same
     * inodes, so agent edits fire inotify), and the panel re-probes git
     * status when working-tree files change. The coalescer already merges
     * inotify bursts (400ms quiet window) and filters .git internals, so
     * the panel's own git probes cannot loop back; the timestamp floor caps
     * auto-refresh at one per 2s and a running mutation (busy) skips the
     * slot — GitCli reloads the panel after its own mutations anyway.
     */
    private fun observeWorkspaceChanges() {
        val app = getApplication<Application>()
        if (!FileChangeHub.watch(sessionId, app)) return
        fileWatchJob = viewModelScope.launch {
            FileChangeHub.eventsFor(sessionId).collect {
                val now = System.currentTimeMillis()
                if (now - lastAutoRefreshMs < AUTO_REFRESH_FLOOR_MS) return@collect
                if (_state.value.busy) return@collect
                lastAutoRefreshMs = now
                refresh()
            }
        }
    }

    /**
     * Resolve the native repo: the session workspace itself or a repo nested
     * below it (host-side counterpart of the sandbox DISCOVER_CMD). Returns
     * the host root plus a [GitRepoInfo] whose `root` is the GUEST path, so
     * sandbox fallback commands and the header stay consistent.
     */
    private suspend fun nativeRepo(): Pair<File, GitRepoInfo>? = withContext(Dispatchers.IO) {
        val ws = GitHostRepo.workspaceDir(app.filesDir, sessionId)
        val (dir, relative) = GitHostRepo.locate(ws) ?: return@withContext null
        val info = runCatching { GitHostRepo.probe(dir) }.getOrNull()
        if (info?.isRepo != true) return@withContext null
        val adopted = relative.substringAfterLast('/')
        dir to info.copy(root = GitHostRepo.guestPath(relative), relativeRoot = adopted)
    }

    /**
     * Full re-probe: repo, status, both numstat maps, branches, stashes,
     * remote and the first history page. The native (JGit) source runs first
     * — no shell, so entry is instant even while the sandbox is booting; the
     * sandbox path is the fallback for workspaces the host probe cannot see.
     * An open diff overlay survives auto-refresh (FileChangeHub fires on
     * every agent edit; closing what the user is reading would make the
     * overlay unusable) — the update below never touches `diff`.
     */
    fun refresh() {
        // A mutation's reload is part of the same single-flight chain: while
        // a mutation is settling, an auto-refresh (FileChangeHub) or a tap on
        // the refresh button could start its probes BEFORE the mutation's
        // writes land and finish AFTER the reload's update — freezing the
        // panel on pre-mutation state with busy already cleared.
        if (_state.value.busy) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val keepDiff = _state.value.diff
            _state.update { it.copy(loading = true, notice = null) }
            // runCatching must NOT swallow CancellationException: a cancelled
            // refresh would fall into the sandbox fallback below, clobber
            // hostDir/guestRoot, and overwrite the newer refresh's paint with
            // a bogus "not a repository" screen. Re-throw instead.
            val native = try {
                nativeRepo()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (native != null) {
                val (dir, info) = native
                hostDir = dir
                guestRoot = info.root
                // [T-git-panel-io-dispatch] Every JGit read rides Dispatchers.IO:
                // the multi-ref walk parses all reachable commit headers — on the
                // Main dispatcher a large repo would freeze the UI for seconds per
                // refresh (FileChangeHub re-runs this every 2s).
                val status = withContext(Dispatchers.IO) { runCatching { GitHostRepo.status(dir) }.getOrNull() }
                val staged = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepoDiff.numstatStaged(dir) }.getOrDefault(emptyMap())
                }
                val unstaged = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepoDiff.numstatUnstaged(dir) }.getOrDefault(emptyMap())
                }
                val remote = withContext(Dispatchers.IO) { runCatching { GitHostRepo.remote(dir) }.getOrNull() }
                // [T-git-panel-refs-degrade] Refs (log + branches) ride native
                // first, but an EMPTY native result on a HEAD-resolvable repo
                // is a failure signal, not an unborn-HEAD answer: an unborn
                // HEAD has NO branch name (probe reports ""), so a non-blank
                // branch + empty refs means the RefDatabase enumeration
                // failed. Degrade to the sandbox CLI (verified healthy in the
                // same sandbox) instead of painting "No commits yet" over a
                // repo with hundreds of commits.
                var log = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepo.logPage(dir, offset = 0, limit = HISTORY_PAGE_SIZE) }
                        .onFailure { Log.w(TAG, "native logPage failed: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
                var branches = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepo.branches(dir) }
                        .onFailure { Log.w(TAG, "native branches failed: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
                val nativeRefsEmpty = log.isEmpty() && branches.isEmpty()
                var sandboxRefsTried = false
                if (preferSandboxRefs || (nativeRefsEmpty && !info.branch.isNullOrBlank())) {
                    sandboxRefsTried = true
                    val sandboxRefs = withContext(Dispatchers.IO) {
                        runCatching {
                            GitCli.logPage(sessionId, offset = 0, root = info.root) to
                                GitCli.branches(sessionId, info.root)
                        }.onFailure {
                            Log.w(TAG, "sandbox refs fallback failed: ${it.javaClass.simpleName}: ${it.message}")
                        }.getOrNull()
                    }
                    if (sandboxRefs != null && (sandboxRefs.first.isNotEmpty() || sandboxRefs.second.isNotEmpty())) {
                        preferSandboxRefs = true
                        log = sandboxRefs.first
                        branches = sandboxRefs.second
                    }
                }
                val stashes = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepo.stashes(dir) }.getOrDefault(emptyList())
                }
                _state.update {
                    it.copy(
                        // [T-git-panel-instant-entry] The host probe already
                        // answered — paint IMMEDIATELY. Identity reconciliation
                        // used to sit BEFORE this point, and its sandbox
                        // round-trip (PRoot boot + 3 commands) held the full-
                        // screen spinner for seconds on EVERY entry, because
                        // repo-local user.name/email is missing in most clones.
                        loading = false,
                        repo = info,
                        status = status,
                        stagedNumstat = staged,
                        unstagedNumstat = unstaged,
                        remote = displayRemote(remote),
                        branches = branches,
                        stashes = stashes,
                        log = log,
                        logHasMore = log.size == HISTORY_PAGE_SIZE,
                        logLoadingMore = false,
                        lastCommit = log.firstOrNull(),
                        diff = keepDiff,
                        // A failed status probe is worth surfacing — a
                        // silently empty Changes tab reads as "clean tree",
                        // which would be a lie. Same honesty for refs: an
                        // empty History on a HEAD-resolvable repo after BOTH
                        // sources failed is a READ FAILURE, not "no commits
                        // yet" (unborn HEAD ⇒ probe branch is blank ⇒ the
                        // sandbox never even tries, so this stays quiet).
                        notice = status?.error ?: when {
                            sandboxRefsTried && log.isEmpty() && branches.isEmpty() &&
                                !info.branch.isNullOrBlank() ->
                                app.getString(R.string.git_panel_history_read_failed)
                            status == null -> "git status failed"
                            else -> null
                        },
                    )
                }
                // Identity reconciliation rides AFTER first paint: the
                // documented chain is repo config -> sandbox config ->
                // default, so fill the gap with the sandbox-resolved
                // identity instead of showing a false "missing identity"
                // warning the sandbox path would never show.
                if (info.userName.isNullOrBlank() || info.userEmail.isNullOrBlank()) {
                    val sandbox = runCatching { GitCli.probe(sessionId) }.getOrNull()
                    val name = info.userName ?: sandbox?.userName
                    val email = info.userEmail ?: sandbox?.userEmail
                    if (name != null || email != null) {
                        _state.update { s ->
                            // Session may have switched while the probe ran.
                            if (s.repo?.root == info.root) {
                                s.copy(repo = s.repo?.copy(userName = s.repo.userName ?: name, userEmail = s.repo.userEmail ?: email))
                            } else s
                        }
                    }
                }
                return@launch
            }
            hostDir = null
            guestRoot = null
            val repo = try {
                GitCli.probe(sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                GitCli.GitRepoInfo(isRepo = false, error = "shell unavailable")
            }
            if (!repo.isRepo) {
                _state.update {
                    it.copy(
                        loading = false,
                        repo = repo,
                        status = null,
                        log = emptyList(),
                        stagedNumstat = emptyMap(),
                        unstagedNumstat = emptyMap(),
                        remote = null,
                        diff = keepDiff,
                    )
                }
                return@launch
            }
            val root = repo.root
            val status = runCatching { GitCli.status(sessionId, root) }.getOrNull()
            val staged = runCatching { GitCli.numstat(sessionId, staged = true, root = root) }
                .getOrDefault(emptyMap())
            val unstaged = runCatching { GitCli.numstat(sessionId, staged = false, root = root) }
                .getOrDefault(emptyMap())
            val remote = runCatching { GitCli.remote(sessionId, root) }.getOrNull()
            val log = runCatching { GitCli.logPage(sessionId, offset = 0, root = root) }
                .getOrDefault(emptyList())
            val branches = runCatching { GitCli.branches(sessionId, root) }.getOrDefault(emptyList())
            val stashes = runCatching { GitCli.stashes(sessionId, root) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    repo = repo,
                    status = status,
                    stagedNumstat = staged,
                    unstagedNumstat = unstaged,
                    remote = displayRemote(remote),
                    branches = branches,
                    stashes = stashes,
                    log = log,
                    logHasMore = log.size == HISTORY_PAGE_SIZE,
                    logLoadingMore = false,
                    lastCommit = log.firstOrNull(),
                    diff = keepDiff,
                    notice = status?.error ?: if (status == null) repo.error ?: "git status failed" else null,
                )
            }
        }
    }

    fun setDraftMessage(value: String) {
        _state.update { it.copy(draftMessage = value) }
    }

    /**
     * Stage/unstage one file: the host path mutates the index via JGit; a
     * host failure (index lock, object db issue) falls back to the sandbox
     * command so the user still sees the operation land.
     */
    fun toggleStage(entry: GitFileEntry) {
        val dir = hostDir
        mutate {
            if (dir != null) {
                withContext(Dispatchers.IO) {
                    try {
                        GitHostRepo.setStaged(dir, entry.path, staged = !entry.staged)
                        null
                    } catch (e: Exception) {
                        // Report the FALLBACK's outcome: surfacing the host
                        // exception while the sandbox call landed would claim
                        // failure for a successful operation.
                        GitCli.setStaged(sessionId, entry, staged = !entry.staged, root = guestRoot)
                    }
                }
            } else {
                GitCli.setStaged(sessionId, entry, staged = !entry.staged, root = guestRoot)
            }
        }
    }

    /** Stage everything (added/changed/deleted). */
    fun stageAll() {
        val dir = hostDir
        mutate {
            if (dir != null) {
                withContext(Dispatchers.IO) {
                    try {
                        GitHostRepo.stageAll(dir)
                        null
                    } catch (e: Exception) {
                        GitCli.stageAll(sessionId, guestRoot)
                    }
                }
            } else {
                GitCli.stageAll(sessionId, guestRoot)
            }
        }
    }

    /**
     * [T-git-panel-mutation-gate] Atomic mutation gate: the busy CHECK and
     * the busy SET used to live on different threads (caller vs launch body),
     * so a fast double-tap ran two mutations concurrently (two checkouts
     * racing, double commit). compareAndSet closes the TOCTOU window; the
     * flag clears when the post-mutation reload finishes — the same place
     * `busy` clears, so the gate and the UI state can never drift apart.
     */
    private val mutationGate = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun tryBeginMutation(): Boolean = mutationGate.compareAndSet(false, true)

    private fun mutate(op: suspend () -> String?) {
        if (!tryBeginMutation()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val error = runCatching { op() }.getOrElse { it.message ?: "failed" }
            reloadAfterMutation(error)
        }
    }

    /**
     * Push to upstream; on success the reload clears the ahead count.
     * [#33] Native JGit first (host path, no PRoot round-trip) whenever the
     * remote's credentials are visible to the host — embedded in the URL or
     * not needed at all; otherwise falls back to the sandbox shell, which
     * owns the credential store.
     */
    fun push() {
        mutate {
            when (val r = hostDir?.let { GitHostRepoTransfer.push(it) }) {
                null, GitHostRepoTransfer.Result.Fallback -> GitCli.push(sessionId, guestRoot) ?: "pushed"
                is GitHostRepoTransfer.Result.Done -> r.message ?: "pushed"
                is GitHostRepoTransfer.Result.Error -> r.message
            }
        }
    }

    /**
     * Pull (ff-only) — a diverged upstream reports the refusal verbatim.
     * [#33] Native-first, same split as [push]: JGit fetch + explicit
     * fast-forward on the host path, `GitCli.pull --ff-only` as fallback.
     */
    fun pull() {
        mutate {
            when (val r = hostDir?.let { GitHostRepoTransfer.pull(it) }) {
                null, GitHostRepoTransfer.Result.Fallback -> GitCli.pull(sessionId, guestRoot) ?: "pulled"
                is GitHostRepoTransfer.Result.Done -> r.message ?: "pulled"
                is GitHostRepoTransfer.Result.Error -> r.message
            }
        }
    }

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
    fun switchBranch(name: String) {
        mutate {
            val dir = hostDir
            if (dir != null) {
                val hostError = withContext(Dispatchers.IO) { GitHostRepoGraph.checkout(dir, name) }
                if (hostError == null) return@mutate null
                // Host refused (in-progress operation / conflicts) is a real
                // refusal, not a transport failure — surface it verbatim.
                if (!hostError.contains("not a git repository")) return@mutate hostError
            }
            if (!name.contains('/')) {
                GitCli.execSimple(sessionId, "checkout", name, root = guestRoot)
            } else {
                val local = name.substringAfter('/')
                val localExists = _state.value.branches.any { it.name == local && !it.name.contains('/') }
                if (localExists) {
                    GitCli.execSimple(sessionId, "checkout", local, root = guestRoot)
                } else {
                    GitCli.execSimple(sessionId, "checkout", "-b", local, "--track", name, root = guestRoot)
                }
            }
        }
    }

    /**
     * Create a new branch at HEAD and check it out (ZCode's
     * `createBranchAndSwitch`). Empty/whitespace names are refused; an
     * existing branch name checks out in place instead of failing.
     */
    fun createBranchAndCheckout(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        mutate {
            val dir = hostDir
            if (dir != null) {
                val exists = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepo.branches(dir).any { it.name == trimmed && !it.name.contains('/') } }
                        .getOrDefault(false)
                }
                val err = withContext(Dispatchers.IO) { GitHostRepoGraph.checkout(dir, trimmed, create = !exists) }
                if (err == null) return@mutate null
            }
            GitCli.execSimple(sessionId, "checkout", "-b", trimmed, root = guestRoot)
        }
    }

    /**
     * Re-read status + numstats + history window and fold in the error.
     * Runs INSIDE the single-flight chain: a mutation's reload replaces any
     * in-flight refresh (its probes must read post-mutation state), and a
     * refresh started later cancels it in turn.
     */
    private fun reloadAfterMutation(error: String?) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val dir = hostDir
            val status: GitStatus?
            val staged: Map<String, com.openminis.app.git.GitCli.NumStat>
            val unstaged: Map<String, com.openminis.app.git.GitCli.NumStat>
            val log: List<GitCommit>
            val branches: List<com.openminis.app.git.GitCli.GitBranchRef>
            if (dir != null) {
                status = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepo.status(dir) }.getOrNull()
                }
                staged = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepoDiff.numstatStaged(dir) }.getOrDefault(emptyMap())
                }
                unstaged = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepoDiff.numstatUnstaged(dir) }.getOrDefault(emptyMap())
                }
                log = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepo.logPage(dir, offset = 0, limit = HISTORY_PAGE_SIZE) }
                        .getOrDefault(emptyList())
                }
                branches = withContext(Dispatchers.IO) {
                    runCatching { GitHostRepo.branches(dir) }.getOrDefault(emptyList())
                }
            } else {
                status = runCatching { GitCli.status(sessionId, guestRoot) }.getOrNull()
                staged = runCatching { GitCli.numstat(sessionId, staged = true, root = guestRoot) }
                    .getOrDefault(emptyMap())
                unstaged = runCatching { GitCli.numstat(sessionId, staged = false, root = guestRoot) }
                    .getOrDefault(emptyMap())
                log = runCatching { GitCli.logPage(sessionId, offset = 0, root = guestRoot) }
                    .getOrDefault(emptyList())
                branches = runCatching { GitCli.branches(sessionId, guestRoot) }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(
                    busy = false,
                    status = status,
                    stagedNumstat = staged,
                    unstagedNumstat = unstaged,
                    log = log,
                    logHasMore = log.size == HISTORY_PAGE_SIZE,
                    lastCommit = log.firstOrNull(),
                    branches = branches,
                    notice = error,
                )
            }
            // Gate and `busy` clear together — one release point, no drift.
            mutationGate.set(false)
        }
    }

    /** Append the next history page when one exists and none is in flight. */
    fun loadMoreLogs() {
        val s = _state.value
        // A refresh in flight is about to replace the window (log + logHasMore
        // reset) — paginating against the STALE snapshot would append an old
        // offset page on top of the new first page. The footer re-fires once
        // the refresh lands and pagination is wanted again.
        if (s.logLoadingMore || !s.logHasMore || s.repo == null || refreshJob?.isActive == true) return
        val dir = hostDir
        viewModelScope.launch {
            _state.update { it.copy(logLoadingMore = true) }
            val page = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) {
                        GitHostRepo.logPage(dir, offset = s.log.size, limit = HISTORY_PAGE_SIZE)
                    } else {
                        GitCli.logPage(sessionId, offset = s.log.size, root = guestRoot)
                    }
                }.getOrDefault(emptyList())
            }
            val known = s.log.mapTo(HashSet()) { it.hash }
            val fresh = page.filter { known.add(it.hash) }
            _state.update {
                it.copy(
                    log = it.log + fresh,
                    // [T-git-panel-load-more] Two conditions must hold for
                    // another page: a FULL page came back AND it contributed
                    // rows the window has not seen (the multi-ref walk can
                    // interleave the same commits into two pages after a
                    // refresh moved the window — a bare size==50 check looped
                    // forever when a page fully deduped away).
                    logHasMore = page.size == HISTORY_PAGE_SIZE && fresh.isNotEmpty(),
                    logLoadingMore = false,
                )
            }
        }
    }

    fun selectTab(tab: GitPanelTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /** Branch switcher filter (ZCode's "搜索分支" box). */
    fun setBranchSearch(query: String) {
        _state.update { it.copy(branchSearch = query) }
    }

    /** Open the commit-detail sheet and fill it from the native/sandbox path. */
    fun openCommitSheet(hash: String) {
        if (_state.value.busy) return
        val dir = hostDir
        viewModelScope.launch {
            _state.update { it.copy(commitSheet = GitCommitSheet(hash)) }
            val detail = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepoDiff.commitDetail(dir, hash)
                    else GitCli.commitDetail(sessionId, hash, guestRoot)
                }.getOrNull()
            }
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepoDiff.commitNumstat(dir, hash)
                    else GitCli.commitNumstat(sessionId, hash, guestRoot)
                }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(
                    commitSheet = GitCommitSheet(
                        hash = hash,
                        detail = detail,
                        files = files,
                        error = if (detail == null) "commit detail unavailable" else null,
                    ),
                )
            }
        }
    }

    fun closeCommitSheet() {
        _state.update { it.copy(commitSheet = null) }
    }

    /** Diff of one file inside a commit — replaces the sheet (overlay z-order). */
    fun openCommitFileDiff(hash: String, path: String) {
        val dir = hostDir
        viewModelScope.launch {
            _state.update {
                it.copy(
                    commitSheet = null,
                    diff = GitDiffView(path, loading = true, content = ""),
                )
            }
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepoDiff.commitFileDiff(dir, hash, path)
                    else GitCli.commitFileDiff(sessionId, hash, path, guestRoot)
                }.getOrDefault("(diff unavailable)")
            }
            _state.update {
                it.copy(diff = GitDiffView(path, loading = false, content = content))
            }
        }
    }

    fun generateCommitMessage() {
        if (_state.value.generating || _state.value.busy) return
        val dir = hostDir
        viewModelScope.launch {
            _state.update { it.copy(generating = true, notice = null) }
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepoDiff.stagedDiff(dir)
                    else GitCli.stagedDiff(sessionId, guestRoot)
                }.getOrDefault("")
            }
            if (staged.isBlank()) {
                _state.update {
                    it.copy(generating = false, notice = "stage some changes first")
                }
                return@launch
            }
            val message = messageGenerator.generate(staged)
            _state.update {
                it.copy(
                    generating = false,
                    draftMessage = message ?: it.draftMessage,
                    notice = if (message == null) "message generation unavailable" else null,
                )
            }
        }
    }

    fun commit() {
        val message = _state.value.draftMessage.trim()
        // Same atomic gate as [mutate] — a double-tapped commit button used
        // to run two commits concurrently (busy checked on the caller thread,
        // set inside the launch).
        if (message.isEmpty() || !tryBeginMutation()) return
        val dir = hostDir
        viewModelScope.launch {
            _state.update { it.copy(busy = true, notice = null) }
            val outcome: CommitOutcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) {
                        val identity = GitHostRepo.commitIdentity(
                            dir,
                            _state.value.repo?.userName,
                            _state.value.repo?.userEmail,
                        )
                        GitHostRepo.commit(dir, message, identity)
                    } else {
                        GitCli.commit(sessionId, message, guestRoot)
                    }
                }.getOrElse { CommitOutcome(false, it.message ?: "commit failed") }
            }
            if (outcome.ok) {
                _state.update { it.copy(draftMessage = "") }
            }
            reloadAfterMutation(outcome.message.ifBlank { null })
        }
    }

    fun openFileDiff(entry: GitFileEntry) {
        val dir = hostDir
        viewModelScope.launch {
            _state.update {
                it.copy(diff = GitDiffView(entry.path, loading = true, content = ""))
            }
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepoDiff.fileDiff(dir, entry.path, entry.staged, entry.untracked)
                    else GitCli.fileDiff(sessionId, entry, guestRoot)
                }.getOrDefault("(diff unavailable)")
            }
            _state.update {
                it.copy(diff = GitDiffView(entry.path, loading = false, content = content))
            }
        }
    }

    fun openCommitDiff(commit: GitCommit) {
        val dir = hostDir
        viewModelScope.launch {
            _state.update {
                it.copy(
                    diff = GitDiffView("${commit.shortHash} ${commit.subject}", loading = true, content = ""),
                )
            }
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepoDiff.commitDiff(dir, commit.hash)
                    else GitCli.commitDiff(sessionId, commit.hash, guestRoot)
                }.getOrDefault("(diff unavailable)")
            }
            _state.update {
                it.copy(
                    diff = GitDiffView("${commit.shortHash} ${commit.subject}", loading = false, content = content),
                )
            }
        }
    }

    fun closeDiff() {
        _state.update { it.copy(diff = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    override fun onCleared() {
        fileWatchJob?.cancel()
        FileChangeHub.unwatch(sessionId)
        super.onCleared()
    }

    companion object {
        /** logcat tag — same as [GitHostRepo]/[GitHostRepoGraph] (one log stream). */
        private const val TAG = "GitPanel"
        private const val AUTO_REFRESH_FLOOR_MS = 2_000L
        const val HISTORY_PAGE_SIZE = 50
    }
}
