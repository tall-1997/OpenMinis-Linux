package com.openminis.app.ui.knowledge

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.KnowledgeNoteItem
import com.openminis.app.data.repository.KnowledgeNoteRepository
import com.openminis.app.data.repository.KnowledgeNoteVersionItem
import com.openminis.app.knowledge.KnowledgeNoteTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-m8-knowledge-workbench] Backing VM for the knowledge workbench.
 * Filtering (search query + active tag) is composed by the UI over
 * [notes] / [query] / [activeTag]; every mutation reloads the lists.
 */
class KnowledgeWorkbenchViewModel(private val appContext: Context) : ViewModel() {

    private val repository: KnowledgeNoteRepository? get() =
        (appContext as? MinisApp)?.takeIf { it.subsystemsReady() }?.knowledgeNoteRepository

    private val _notes = MutableStateFlow<List<KnowledgeNoteItem>>(emptyList())
    val notes: StateFlow<List<KnowledgeNoteItem>> = _notes.asStateFlow()

    private val _trashed = MutableStateFlow<List<KnowledgeNoteItem>>(emptyList())
    val trashed: StateFlow<List<KnowledgeNoteItem>> = _trashed.asStateFlow()

    private val _versions = MutableStateFlow<List<KnowledgeNoteVersionItem>>(emptyList())
    val versions: StateFlow<List<KnowledgeNoteVersionItem>> = _versions.asStateFlow()

    val query = MutableStateFlow("")
    val activeTag = MutableStateFlow<String?>(null)
    val snackbar = MutableStateFlow<String?>(null)

    init {
        refresh()
    }

    fun setQuery(value: String) { query.value = value }

    fun setTag(tag: String?) { activeTag.value = tag }

    private fun refresh() {
        val repo = repository ?: return
        viewModelScope.launch {
            _notes.value = withContext(Dispatchers.IO) { repo.listActive() }
            _trashed.value = withContext(Dispatchers.IO) { repo.listTrashed() }
        }
    }

    fun create(title: String, bodyMd: String, tags: List<String>, onDone: (String) -> Unit = {}) {
        val repo = repository ?: return
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                repo.createNote(title, bodyMd, tags, System.currentTimeMillis())
            }
            refresh()
            snackbar.value = SnackKey.CREATED
            onDone(item.id)
        }
    }

    fun update(id: String, title: String, bodyMd: String, tags: List<String>) {
        val repo = repository ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updateNote(id, title, bodyMd, tags, System.currentTimeMillis())
            }
            refresh()
            loadVersions(id)
        }
    }

    fun trash(id: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.trashNote(id, System.currentTimeMillis()) }
            refresh()
            snackbar.value = SnackKey.TRASHED
        }
    }

    fun restore(id: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.restoreNote(id) }
            refresh()
            snackbar.value = SnackKey.RESTORED
        }
    }

    fun purge(id: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.purgeNote(id) }
            refresh()
            snackbar.value = SnackKey.PURGED
        }
    }

    fun purgeExpiredTrash() {
        val repo = repository ?: return
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { repo.purgeTrashedBefore(System.currentTimeMillis()) }
            refresh()
            if (n > 0) snackbar.value = SnackKey.PURGED_N
        }
    }

    fun loadVersions(noteId: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            _versions.value = withContext(Dispatchers.IO) { repo.versions(noteId) }
        }
    }

    suspend fun exportJsonl(): String = withContext(Dispatchers.IO) {
        repository?.exportLines() ?: ""
    }

    fun importJsonl(raw: String, onReport: (KnowledgeNoteRepository.ImportReport) -> Unit = {}) {
        val repo = repository ?: return
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { repo.importLines(raw, System.currentTimeMillis()) }
            refresh()
            snackbar.value = SnackKey.IMPORTED
            onReport(report)
        }
    }

    fun consumeSnackbar() { snackbar.value = null }

    /** Snackbar message keys; translated by the screen. */
    object SnackKey {
        const val CREATED = "created"
        const val TRASHED = "trashed"
        const val RESTORED = "restored"
        const val PURGED = "purged"
        const val PURGED_N = "purged_n"
        const val IMPORTED = "imported"
    }
}
