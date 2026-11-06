package com.openminis.app.ui.settings.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.backup.remote.RcloneBackendCatalog
import com.openminis.app.backup.remote.RcloneBridge
import com.openminis.app.backup.remote.RcloneBrowser
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-rclone-ui] Drives the backup DESTINATION management UI — the
 * Android peer of iOS BackupDestinationPicker + RcloneAddServerView. Owns the
 * remote list, the add-server form (connect → browse → choose/create folder),
 * and the pending new-remote's live browse state.
 */
class RcloneDestinationsViewModel(app: Application) : AndroidViewModel(app) {

    // [T-android-sessionlist-prefs-io] RcloneRemoteStore's first touch parses
    // + decrypts the prefs file — main-thread work. Both the field and the
    // initial remotes read were eager, so merely CONSTRUCTING this ViewModel
    // (before any of its UI showed) did that IO on the main thread. The store
    // is now lazy and the initial list loads in an IO coroutine; the flow
    // starts empty and fills in.
    private val store by lazy { RcloneRemoteStore(app) }

    private val _remotes = MutableStateFlow<List<RcloneRemoteStore.Remote>>(emptyList())
    val remotes: StateFlow<List<RcloneRemoteStore.Remote>> = _remotes.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _remotes.value = runCatching { store.remotes }.getOrDefault(emptyList())
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Live folder-browse state for the add-server flow, null until connected. */
    private val _browse = MutableStateFlow<BrowseState?>(null)
    val browse: StateFlow<BrowseState?> = _browse.asStateFlow()

    data class BrowseState(
        val remote: RcloneRemoteStore.Remote,
        val path: String,
        val entries: List<RcloneBrowser.Entry>,
        // [T-android-backup-dest-edit] True when the browser was opened from a
        // saved destination's detail page: the chosen folder then re-points the
        // EXISTING remote (applyFolder) instead of saving the pending new one
        // (savePending).
        val existing: Boolean = false,
    )

    /**
     * True once a connection attempt has been refused because the server's TLS
     * certificate could not be verified. Drives the "trust this certificate"
     * opt-in, which is deliberately hidden until this happens.
     */
    private val _certificateRejected = MutableStateFlow(false)
    val certificateRejected: StateFlow<Boolean> = _certificateRejected.asStateFlow()

    // [T-android-sessionlist-prefs-io] refresh/setEnabled/remove all touch the
    // prefs-backed store (read or write) — moved onto Dispatchers.IO, same
    // pattern as browsePhoneFolderForRestore in BackupViewModel.
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _remotes.value = runCatching { store.remotes }.getOrDefault(emptyList())
        }
    }
    fun clearError() { _error.value = null }

    /** Reset the certificate prompt when the user edits the form or backs out. */
    fun clearCertificateRejection() { _certificateRejected.value = false }

    fun setEnabled(name: String, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.setEnabled(name, on) }
            _remotes.value = runCatching { store.remotes }.getOrDefault(emptyList())
        }
    }

    fun remove(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.remove(name) }
            _remotes.value = runCatching { store.remotes }.getOrDefault(emptyList())
        }
    }

    // MARK: - [T-android-backup-dest-edit] Editing a saved destination

    /** Whether [name] has a stored secret — drives the editor's footer, never shows it. */
    fun hasSecret(name: String): Boolean = store.hasSecret(name)

    /**
     * Rename a destination. History records follow the new name, and the old
     * rclone section is dropped so its credential does not linger under a name
     * nothing refers to any more.
     */
    fun rename(name: String, newName: String, onDone: (RcloneRemoteStore.Remote) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val r = store.update(name = name, newName = newName)
                    if (r.name != name) {
                        com.openminis.app.backup.BackupHistory.get(getApplication())
                            .renameDestination(name, r.name)
                        store.dropRcloneSection(name)
                        store.syncToRclone()
                    }
                    r
                }
                refresh()
                onDone(updated)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't rename the destination."
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Save edited connection settings — only if they connect.
     *
     * [values] are the form's non-secret fields. The candidate is probed under
     * a throwaway rclone section first ([RcloneRemoteStore.probeConnection]);
     * on failure nothing is written and the destination keeps working with its
     * old settings and password.
     */
    fun saveConnection(
        name: String,
        values: Map<String, String>,
        secret: RcloneRemoteStore.SecretEdit,
        allowInsecureTLS: Boolean,
        onDone: (RcloneRemoteStore.Remote) -> Unit,
    ) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val r = store.update(
                        name = name,
                        newParams = values,
                        secret = secret,
                        allowInsecureTLS = allowInsecureTLS,
                        verify = { candidate, candidateSecret ->
                            try {
                                store.probeConnection(candidate, candidateSecret)
                            } catch (e: Exception) {
                                throw ProbeFailed(e)
                            }
                        },
                    )
                    store.syncToRclone()
                    r
                }
                _certificateRejected.value = false
                refresh()
                onDone(updated)
            } catch (e: ProbeFailed) {
                val msg = e.cause?.message ?: "Could not connect to the server."
                AppLogger.error(TAG, "[Rclone] edit of '$name' not saved, connection failed: $msg")
                _error.value = getApplication<Application>()
                    .getString(com.openminis.app.R.string.backup_dest_edit_failed, msg)
                if (!allowInsecureTLS && RcloneRemoteStore.looksLikeCertificateFailure(msg)) {
                    _certificateRejected.value = true
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't save the connection."
            } finally {
                _busy.value = false
            }
        }
    }

    /** Wraps a failed connection probe so it is told apart from a store error. */
    private class ProbeFailed(cause: Exception) : Exception(cause.message, cause)

    /** List [name]'s backup folder; reports "Connected." or the rclone error. */
    fun testConnection(name: String, onResult: (ok: Boolean, message: String?) -> Unit) {
        val r = store.remote(name) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.syncToRclone()
                    RcloneBrowser.listDirectories(r, r.listRoot)
                }
            }
            onResult(
                result.isSuccess,
                result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            )
        }
    }

    /** Open the folder browser on a saved server, starting at its current folder. */
    fun browseExisting(name: String) {
        val r = store.remote(name) ?: return
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    store.syncToRclone()
                    RcloneBrowser.listDirectories(r, r.listRoot)
                }
                _browse.value = BrowseState(r, r.listRoot, entries, existing = true)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not open that folder."
            } finally {
                _busy.value = false
            }
        }
    }

    /** Store the folder picked in [browseExisting]'s browser. */
    fun applyFolder(path: String, onDone: (RcloneRemoteStore.Remote) -> Unit) {
        val b = _browse.value ?: return
        _busy.value = true
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    store.update(name = b.remote.name, newPath = path)
                }
                refresh()
                _browse.value = null
                onDone(updated)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save the folder."
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Register a candidate remote in rclone's in-memory config and list its
     * root, so the add-server form can browse for a destination folder BEFORE
     * the remote is permanently saved. Uses a temporary name; the real save
     * happens in [savePending].
     */
    fun connectAndBrowse(
        backend: String,
        displayName: String,
        values: Map<String, String>,
        startPath: String,
        allowInsecureTLS: Boolean = false,
        // [T-android-connect-and-save] Run only after the connection has
        // actually succeeded. Null keeps the old behaviour (stop in the folder
        // browser); the restore host passes a save-and-return closure.
        onConnected: (() -> Unit)? = null,
    ) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            var connected = false
            try {
                val candidate = withContext(Dispatchers.IO) {
                    val nonSecret = values.filterKeys { key ->
                        RcloneBackendCatalog.backend(backend)?.fields
                            ?.firstOrNull { it.key == key }?.isSecret != true
                    }
                    val secretKeyName = if (backend == "s3") "secret_access_key" else "pass"
                    val secret = values[secretKeyName]
                    // The candidate's certificate choice has to be live for THIS
                    // connection test, not just persisted at save time —
                    // otherwise "Trust this certificate" would appear to do
                    // nothing until after the destination was already saved.
                    RcloneBridge.setInsecureTLS(
                        allowInsecureTLS || store.remotes.any { it.allowInsecureTLS }
                    )
                    // Register under the intended name so fsSpec is stable; a
                    // later savePending persists the same config.
                    registerEphemeral(displayName, backend, nonSecret, secret, allowInsecureTLS)
                    val remote = RcloneRemoteStore.Remote(
                        name = displayName, backend = backend,
                        params = nonSecret, path = startPath,
                        allowInsecureTLS = allowInsecureTLS,
                    )
                    val entries = RcloneBrowser.listDirectories(remote, startPath)
                    BrowseState(remote, startPath, entries)
                }
                _browse.value = candidate
                _certificateRejected.value = false
                // Only set when the listing above did not throw, so a failed
                // connection can never save a server. Deliberately NOT invoked
                // here: `savePending` sets `busy` itself, and this coroutine's
                // `finally` would immediately clear it again — leaving the UI
                // idle mid-save and re-enterable. Fired after the flag settles.
                connected = true
            } catch (e: Exception) {
                // Never log the exception body verbatim at error level with the
                // values map in scope — the message can echo the URL, and the
                // credential is deliberately never part of it.
                AppLogger.error(TAG, "[Rclone] connect failed: ${e.message}")
                _error.value = e.message ?: "Could not connect to the server."
                // Only offer the escape hatch once the server has actually been
                // rejected on certificate grounds. A permanently visible "don't
                // verify certificates" checkbox next to a password field invites
                // switching off a protection that was never in the way.
                if (!allowInsecureTLS &&
                    RcloneRemoteStore.looksLikeCertificateFailure(e.message)
                ) {
                    _certificateRejected.value = true
                }
            } finally {
                _busy.value = false
            }
            if (connected) onConnected?.invoke()
        }
    }

    /** Navigate the browser into [entry] (or up when [entry] is null → parent). */
    fun navigateTo(path: String) {
        val b = _browse.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    RcloneBrowser.listDirectories(b.remote, path)
                }
                _browse.value = b.copy(path = path, entries = entries)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not open that folder."
            } finally {
                _busy.value = false
            }
        }
    }

    fun createFolder(name: String) {
        val b = _browse.value ?: return
        if (_busy.value || name.isBlank()) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val newPath = withContext(Dispatchers.IO) {
                    RcloneBrowser.createFolder(b.remote, b.path, name)
                }
                val entries = withContext(Dispatchers.IO) {
                    RcloneBrowser.listDirectories(b.remote, b.path)
                }
                _browse.value = b.copy(entries = entries)
                // Descend into the folder just created — matches iOS behaviour.
                navigateTo(newPath)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not create the folder."
            } finally {
                _busy.value = false
            }
        }
    }

    /** Persist the browsed candidate as a real remote, writing at [path]. */
    fun savePending(path: String, onDone: () -> Unit) {
        val b = _browse.value ?: return
        _busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val secretKeyName = if (b.remote.backend == "s3") "secret_access_key" else "pass"
                    // The secret was passed to registerEphemeral; re-read it from
                    // the live rclone config is not possible, so we require the
                    // caller to have kept it — instead we persist non-secret
                    // params + the secret we still hold on the browse candidate's
                    // registration. To keep the secret, connectAndBrowse stashed
                    // it; here we re-store via the store's add (which re-obscures).
                    store.add(
                        name = b.remote.name,
                        backend = b.remote.backend,
                        params = b.remote.params,
                        secret = pendingSecret,
                        path = path,
                        allowInsecureTLS = b.remote.allowInsecureTLS,
                    )
                    store.syncToRclone()
                }
                refresh()
                _browse.value = null
                pendingSecret = null
                onDone()
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save the destination."
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * [T-android-connect-and-save] Persist the just-connected server at the
     * path the connection landed on, skipping the folder picker.
     *
     * For a restore source that path is only where browsing starts — the user
     * navigates from there to find the `.minisbak` — so there is nothing to
     * decide before saving. [savePending] remains the backup-destination path,
     * where the chosen folder is where files get written.
     */
    fun saveConnected(onDone: (String) -> Unit) {
        val b = _browse.value ?: return
        savePending(b.path) { onDone(b.remote.name) }
    }

    fun cancelPending() {
        _browse.value = null
        pendingSecret = null
    }

    // The secret is held only in memory between connect and save; never logged.
    @Volatile private var pendingSecret: String? = null

    private fun registerEphemeral(
        name: String,
        backend: String,
        nonSecret: Map<String, String>,
        secret: String?,
        allowInsecureTLS: Boolean = false,
    ) {
        pendingSecret = secret
        // Built by the SAME function syncToRclone uses. This path used to
        // assemble its own config — obscuring every backend's secret and
        // mapping the key inline — so an S3 destination passed its add-time
        // test (which signed with the plaintext just typed) and then failed
        // every later list and upload with SignatureDoesNotMatch. If the two
        // ever disagree again, the failure is invisible until backup or,
        // worse, until restore.
        val params = RcloneRemoteStore.buildConfigParams(
            backend = backend,
            params = nonSecret,
            secret = secret,
            obscure = { clear ->
                runCatching {
                    RcloneBridge.rpc("core/obscure", mapOf("clear" to clear))
                        .optString("obscured").takeIf { it.isNotEmpty() }
                }.getOrNull()
            },
            allowInsecureTLS = allowInsecureTLS,
        )
        RcloneBridge.rpc(
            "config/create",
            mapOf(
                "name" to name, "type" to backend, "parameters" to params,
                "opt" to mapOf("nonInteractive" to true),
            ),
        )
    }

    companion object {
        private const val TAG = "Rclone"
    }
}
