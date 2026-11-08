package com.openminis.app.ui.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.git.GitCli.CommitOutcome
import com.openminis.app.git.GitBranchRef
import com.openminis.app.git.GitCli
import com.openminis.app.git.GitCli.GitCommit
import com.openminis.app.git.GitCommitMessageGenerator
import com.openminis.app.git.GitCli.GitFileEntry
import com.openminis.app.git.GitCli.GitRepoInfo
import com.openminis.app.git.GitCli.GitStatus
import com.openminis.app.git.GitStashEntry
import com.openminis.app.sandbox.FileChangeHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the panel renders, in one immutable snapshot. */
data class GitPanelState(
    val loading: Boolean = true,
    val repo: GitRepoInfo? = null,
    val status: GitStatus? = null,
    val log: List<GitCommit> = emptyList(),
    /** Local + remote branches for the switcher (current first). */
    val branches: List<GitBranchRef> = emptyList(),
    val stashes: List<GitStashEntry> = emptyList(),
    /** A mutating git call (stage/commit/sync/checkout) is in flight — buttons dim. */
    val busy: Boolean = false,
    /** The AI commit-message call is in flight. */
    val generating: Boolean = false,
    val draftMessage: String = "",
    /** Transient one-line result (commit ok, error text…). Cleared on refresh. */
    val notice: String? = null,
    val diff: GitDiffView? = null,
)

data class GitDiffView(
    val title: String,
    val loading: Boolean,
    val content: String,
)

/**
 * Drives the Git panel for one chat session's workspace.
 *
 * Every git call goes through [com.openminis.app.sandbox.ExecutionCoordinator]
 * — the session's persistent PRoot shell — so the panel sees exactly the tree
 * the agent sees, and the per-session mutex serialises panel commands with
 * the agent's own `git` invocations.
 */
class GitPanelViewModel(
    app: Application,
    private val sessionId: String,
    providerRepository: ProviderRepository,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(GitPanelState())
    val state: StateFlow<GitPanelState> = _state.asStateFlow()

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

    fun refresh() {
        viewModelScope.launch {
            // [T-git-panel-v2] An open diff overlay must survive auto-refresh:
            // FileChangeHub fires on every agent edit, and closing what the user
            // is reading because the tree changed makes the overlay unusable.
            val keepDiff = _state.value.diff
            _state.update { it.copy(loading = true, notice = null, diff = null) }
            val repo = GitCli.probe(sessionId)
            if (!repo.isRepo) {
                _state.update {
                    it.copy(loading = false, repo = repo, status = null, log = emptyList())
                }
                return@launch
            }
            val root = repo.root
            val status = runCatching { GitCli.status(sessionId, root) }.getOrNull()
            val log = runCatching { GitCli.log(sessionId, root = root) }.getOrDefault(emptyList())
            val branches = runCatching { GitCli.branches(sessionId, root) }.getOrDefault(emptyList())
            val stashes = runCatching { GitCli.stashes(sessionId, root) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    repo = repo,
                    status = status,
                    log = log,
                    branches = branches,
                    stashes = stashes,
                    diff = keepDiff,
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

    /** Re-read status (and log after commits) and fold in the error, if any. */
    private suspend fun reloadAfterMutation(error: String?) {
        val root = _state.value.repo?.root
        val status = runCatching { GitCli.status(sessionId, root) }.getOrNull()
        val log = runCatching { GitCli.log(sessionId, root = root) }.getOrDefault(emptyList())
        val branches = runCatching { GitCli.branches(sessionId, root) }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                busy = false,
                status = status,
                log = log,
                // A checkout changed the branch: keep the switcher honest.
                branches = branches,
                notice = error,
            )
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

    /** Push to upstream; on success the reload clears the ahead count. */
    fun push() {
        mutate { GitCli.push(sessionId, _state.value.repo?.root) ?: "pushed" }
    }

    /** Pull (ff-only) — a diverged upstream reports git's refusal verbatim. */
    fun pull() {
        mutate { GitCli.pull(sessionId, _state.value.repo?.root) ?: "pulled" }
    }

    /** Check out [name] (local) or create a local tracking branch for it. */
    fun switchBranch(name: String) {
        val root = _state.value.repo?.root
        mutate {
            if (_state.value.branches.any { !it.name.contains('/') && it.name == name }) {
                GitCli.execSimple(sessionId, "checkout", name, root = root) ?: "checked out $name"
            } else {
                GitCli.execSimple(sessionId, "checkout", "-b", name, "--track", name, root = root)
                    ?: "created $name"
            }
        }
    }

    fun openCommitDiff(commit: GitCommit) {
        viewModelScope.launch {
            _state.update {
                it.copy(diff = GitDiffView("${commit.shortHash} ${commit.subject}", loading = true, content = ""))
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
    }
}
