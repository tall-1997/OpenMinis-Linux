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
import com.openminis.app.sandbox.FileChangeHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
     * Full re-probe: repo, status, both numstat maps, remote and the first
     * history page. The extra probes are independent and best-effort — a
     * failed remote probe only blanks the header card's remote line.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, notice = null, diff = null) }
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
            _state.update {
                it.copy(
                    loading = false,
                    repo = repo,
                    status = status,
                    stagedNumstat = staged,
                    unstagedNumstat = unstaged,
                    remote = displayRemote(remote),
                    log = log,
                    logHasMore = log.size == HISTORY_PAGE_SIZE,
                    logLoadingMore = false,
                    lastCommit = log.firstOrNull(),
                    // A failed status probe (e.g. shell died mid-refresh) is
                    // worth surfacing — a silently empty Changes tab reads as
                    // "clean tree", which would be a lie.
                    notice = if (status == null) repo.error ?: "git status failed" else null,
                )
            }
        }
    }

    fun setDraftMessage(value: String) {
        _state.update { it.copy(draftMessage = value) }
    }

    fun toggleStage(entry: GitFileEntry) {
        val root = _state.value.repo?.root
        mutate { GitCli.setStaged(sessionId, entry, staged = !entry.staged, root = root) }
    }

    fun stageAll() {
        val root = _state.value.repo?.root
        mutate { GitCli.stageAll(sessionId, root) }
    }

    private fun mutate(op: suspend () -> String?) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val error = runCatching { op() }.getOrElse { it.message ?: "failed" }
            reloadAfterMutation(error)
        }
    }

    /** Re-read status + numstats + history window and fold in the error. */
    private suspend fun reloadAfterMutation(error: String?) {
        val root = _state.value.repo?.root
        val status = runCatching { GitCli.status(sessionId, root) }.getOrNull()
        val staged = runCatching { GitCli.numstat(sessionId, staged = true, root = root) }
            .getOrDefault(emptyMap())
        val unstaged = runCatching { GitCli.numstat(sessionId, staged = false, root = root) }
            .getOrDefault(emptyMap())
        val log = runCatching { GitCli.logPage(sessionId, offset = 0, root = root) }
            .getOrDefault(emptyList())
        _state.update {
            it.copy(
                busy = false,
                status = status,
                stagedNumstat = staged,
                unstagedNumstat = unstaged,
                log = log,
                logHasMore = log.size == HISTORY_PAGE_SIZE,
                lastCommit = log.firstOrNull(),
                notice = error,
            )
        }
    }

    /** Append the next history page when one exists and none is in flight. */
    fun loadMoreLogs() {
        val s = _state.value
        if (s.logLoadingMore || !s.logHasMore || s.repo == null) return
        viewModelScope.launch {
            _state.update { it.copy(logLoadingMore = true) }
            val page = runCatching {
                GitCli.logPage(sessionId, offset = s.log.size, root = s.repo.root)
            }.getOrDefault(emptyList())
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

    /** Open the commit-detail sheet and fill it from `git show`. */
    fun openCommitSheet(hash: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(commitSheet = GitCommitSheet(hash)) }
            val root = _state.value.repo?.root
            val detail = runCatching { GitCli.commitDetail(sessionId, hash, root) }.getOrNull()
            val files = runCatching { GitCli.commitNumstat(sessionId, hash, root) }
                .getOrDefault(emptyList())
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
        viewModelScope.launch {
            _state.update {
                it.copy(
                    commitSheet = null,
                    diff = GitDiffView(path, loading = true, content = ""),
                )
            }
            val content = runCatching {
                GitCli.commitFileDiff(sessionId, hash, path, _state.value.repo?.root)
            }.getOrDefault("(diff unavailable)")
            _state.update {
                it.copy(diff = GitDiffView(path, loading = false, content = content))
            }
        }
    }

    fun generateCommitMessage() {
        if (_state.value.generating || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(generating = true, notice = null) }
            val staged = runCatching { GitCli.stagedDiff(sessionId, _state.value.repo?.root) }
                .getOrDefault("")
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
        viewModelScope.launch {
            _state.update { it.copy(busy = true, notice = null) }
            val outcome: CommitOutcome = runCatching {
                GitCli.commit(sessionId, message, _state.value.repo?.root)
            }
                .getOrElse { CommitOutcome(false, it.message ?: "commit failed") }
            if (outcome.ok) {
                _state.update { it.copy(draftMessage = "") }
            }
            reloadAfterMutation(outcome.message.ifBlank { null })
        }
    }

    fun openFileDiff(entry: GitFileEntry) {
        viewModelScope.launch {
            _state.update {
                it.copy(diff = GitDiffView(entry.path, loading = true, content = ""))
            }
            val content = runCatching {
                GitCli.fileDiff(sessionId, entry, _state.value.repo?.root)
            }
                .getOrDefault("(diff unavailable)")
            _state.update {
                it.copy(diff = GitDiffView(entry.path, loading = false, content = content))
            }
        }
    }

    fun openCommitDiff(commit: GitCommit) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    diff = GitDiffView("${commit.shortHash} ${commit.subject}", loading = true, content = ""),
                )
            }
            val content = runCatching {
                GitCli.commitDiff(sessionId, commit.hash, _state.value.repo?.root)
            }
                .getOrDefault("(diff unavailable)")
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
