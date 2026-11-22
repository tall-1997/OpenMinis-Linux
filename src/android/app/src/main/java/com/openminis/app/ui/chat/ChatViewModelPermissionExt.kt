package com.openminis.app.ui.chat

import com.openminis.app.data.SessionPermissionPrefs
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
/**
     * [T-perm-three-modes] Switch the session tier and make the choice durable.
     *
     * Two failure modes this method has to close:
     *1. The row write used to be a `viewModelScope.launch`; leaving the chat
     * right after the tap cancelled it, so the stored mode stayed stale.
     *2. A chat with no stored choice forgot the user's last pick, so every
     * new conversation started over from the shipped default.
     */
    internal fun ChatViewModel.setSessionPermissionMode(mode: com.openminis.app.security.SessionPermissionMode) {
        _permissionMode.value = mode
        applyGateMode(mode)
        // [T-perm-inherit] Remember the choice for chats that do not carry
        // their own yet (ZCode: new sessions inherit the selected mode).
        // [T-perm-full-access-not-inherited] FULL_ACCESS is a per-session
        // authorisation, not a default: inheriting it would make one
        // confirmation permanent AND skip the inline risk dialog, which only
        // runs when the user taps the item.
        if (!mode.isFullAccess()) com.openminis.app.data.SessionPermissionPrefs.remember(mode)
        val sid = realSessionId.ifEmpty { sessionId }
        if (isDraft || sid.isBlank() || sid.startsWith("__new__")) return
        // [T-perm-persist-await] This used to be a `viewModelScope.launch`:
        // leaving the chat right after tapping the pill cancelled the write, so
        // the row kept the old mode and the choice was silently lost. The write
        // now runs on the application scope, which outlives this ViewModel, and
        // the new mode is committed on Dispatchers.IO in call order.
        val repo = chatRepository
        val token = mode.toStoredToken()
        // [T-perm-write-order] Two quick taps must not race: Room suspend
        // writes keep no call order, so the last tap could be overwritten.
        // Serialise per session, and never crash a preview/robolectric host
        // that is not a MinisApp.
        val app = context.applicationContext as? com.openminis.app.MinisApp
        if (app == null) return
        val scope = app.appCoroutineScopes.io
        writeLockFor(sid).let { gate -> scope.launch { gate.withLock {
            runCatching { repo.dao.updatePermissionMode(sid, token) }.onFailure {
                com.openminis.app.logging.AppLogger.warning(
                    ChatViewModel.TAG,
                    "permission mode not persisted (sid=$sid token=$token): ${it.javaClass.simpleName}",
                )
            }
        } } }
    }

    private fun writeLockFor(sid: String): kotlinx.coroutines.sync.Mutex =
        permissionWriteLocks.computeIfAbsent(sid) { kotlinx.coroutines.sync.Mutex() }

    private val permissionWriteLocks =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
