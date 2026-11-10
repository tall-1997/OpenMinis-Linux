package com.openminis.app.ui.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.git.GitCli
import com.openminis.app.git.GitCli.CommitOutcome
import com.openminis.app.git.GitCli.GitCommit
import com.openminis.app.git.GitCli.GitFileEntry
import com.openminis.app.git.GitCommitMessageGenerator
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
        viewModelScope.launch {
            val keepDiff = _state.value.diff
            _state.update { it.copy(loading = true, notice = null) }
            val native = runCatching { nativeRepo() }.getOrNull()
            if (native != null) {
                val (dir, info) = native
                hostDir = dir
                guestRoot = info.root
                var resolved = info
                if (resolved.userName.isNullOrBlank() || resolved.userEmail.isNullOrBlank()) {
                    // The host probe reads repo-local config only; the
                    // documented chain is repo config -> sandbox config ->
                    // default, so fill the gap with the sandbox-resolved
                    // identity instead of showing a false "missing identity"
                    // warning the sandbox path would never show.
                    val sandbox = runCatching { GitCli.probe(sessionId) }.getOrNull()
                    resolved = resolved.copy(
                        userName = resolved.userName ?: sandbox?.userName,
                        userEmail = resolved.userEmail ?: sandbox?.userEmail,
                    )
                }
                val status = runCatching { GitHostRepo.status(dir) }.getOrNull()
                val staged = runCatching { GitHostRepo.numstatStaged(dir) }
                    .getOrDefault(emptyMap())
                val unstaged = runCatching { GitHostRepo.numstatUnstaged(dir) }
                    .getOrDefault(emptyMap())
                val remote = runCatching { GitHostRepo.remote(dir) }.getOrNull()
                val log = runCatching { GitHostRepo.logPage(dir, offset = 0, limit = HISTORY_PAGE_SIZE) }
                    .getOrDefault(emptyList())
                val branches = runCatching { GitHostRepo.branches(dir) }.getOrDefault(emptyList())
                val stashes = runCatching { GitHostRepo.stashes(dir) }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        loading = false,
                        repo = resolved,
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
                        // which would be a lie.
                        notice = if (status == null) "git status failed" else null,
                    )
                }
                return@launch
            }
            hostDir = null
            guestRoot = null
            val repo = GitCli.probe(sessionId)
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
                    notice = if (status == null) repo.error ?: "git status failed" else null,
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

    private fun mutate(op: suspend () -> String?) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val error = runCatching { op() }.getOrElse { it.message ?: "failed" }
            reloadAfterMutation(error)
        }
    }

    /** Push to upstream; on success the reload clears the ahead count. */
    fun push() {
        mutate { GitCli.push(sessionId, guestRoot) ?: "pushed" }
    }

    /** Pull (ff-only) — a diverged upstream reports git's refusal verbatim. */
    fun pull() {
        mutate { GitCli.pull(sessionId, guestRoot) ?: "pulled" }
    }

    /**
     * Check out [name]: a local branch directly, a remote branch by creating
     * the local tracking branch named after its short name (`origin/main` →
     * `main`). Creating a local branch literally named "origin/main" would
     * collide with the remote-tracking namespace, so the short name is the
     * only legal target; when it already exists we check it out directly.
     * Checkout stays on the sandbox path — after it lands, the host-side
     * JGit reads the same .git and the reload reflects the new branch.
     */
    fun switchBranch(name: String) {
        mutate {
            if (!name.contains('/')) {
                GitCli.execSimple(sessionId, "checkout", name, root = guestRoot) ?: "checked out $name"
            } else {
                val local = name.substringAfter('/')
                val localExists = _state.value.branches.any { it.name == local && !it.name.contains('/') }
                if (localExists) {
                    GitCli.execSimple(sessionId, "checkout", local, root = guestRoot)
                        ?: "checked out $local"
                } else {
                    GitCli.execSimple(sessionId, "checkout", "-b", local, "--track", name, root = guestRoot)
                        ?: "created $local from $name"
                }
            }
        }
    }

    /** Re-read status + numstats + history window and fold in the error. */
    private suspend fun reloadAfterMutation(error: String?) {
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
                runCatching { GitHostRepo.numstatStaged(dir) }.getOrDefault(emptyMap())
            }
            unstaged = withContext(Dispatchers.IO) {
                runCatching { GitHostRepo.numstatUnstaged(dir) }.getOrDefault(emptyMap())
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
    }

    /** Append the next history page when one exists and none is in flight. */
    fun loadMoreLogs() {
        val s = _state.value
        if (s.logLoadingMore || !s.logHasMore || s.repo == null) return
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
            _state.update {
                it.copy(
                    log = it.log + page.filter { c -> known.add(c.hash) },
                    logHasMore = page.size == HISTORY_PAGE_SIZE,
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

    /** Open the commit-detail sheet and fill it from the native/sandbox path. */
    fun openCommitSheet(hash: String) {
        if (_state.value.busy) return
        val dir = hostDir
        viewModelScope.launch {
            _state.update { it.copy(commitSheet = GitCommitSheet(hash)) }
            val detail = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepo.commitDetail(dir, hash)
                    else GitCli.commitDetail(sessionId, hash, guestRoot)
                }.getOrNull()
            }
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    if (dir != null) GitHostRepo.commitNumstat(dir, hash)
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
                    if (dir != null) GitHostRepo.commitFileDiff(dir, hash, path)
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
                    if (dir != null) GitHostRepo.stagedDiff(dir)
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
        if (message.isEmpty() || _state.value.busy) return
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
                    if (dir != null) GitHostRepo.fileDiff(dir, entry.path, entry.staged, entry.untracked)
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
                    if (dir != null) GitHostRepo.commitDiff(dir, commit.hash)
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
        private const val AUTO_REFRESH_FLOOR_MS = 2_000L
        const val HISTORY_PAGE_SIZE = 50
    }
}
