package com.openminis.app.ui.chat

/** Ensure the session exists in the database. Called before first message. */
internal suspend fun ChatViewModel.ensureSession(): String {
    if (realSessionId.isNotEmpty()) return realSessionId
    val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
    // [T-memory-global-toggle-settings-ui-android] Snapshot the
    // current in-memory `_memoryEnabled` into the new row. For a
    // draft VM this matches the global default we seeded at
    // construction; if the user flipped /memory on the draft
    // before first send, that choice wins.
    val session = chatRepository.createSession(
        modelId = modelId,
        memoryEnabled = _memoryEnabled.value,
        permissionMode = _permissionMode.value.name,
    )
    realSessionId = session.id
    // [T-draft-approval-key-drift] Carry any session-scoped gate state the
    // user granted under the draft key (allow-all, per-tool grants, in-flight
    // approval requests) over to the persisted id BEFORE the first tool call
    // runs — otherwise a "全部允许" tapped on the draft stops matching, and
    // an approval request filed pre-persist becomes unreachable.
    com.openminis.app.service.ApprovalGate.migrateSession(oldSid = sessionId, newSid = session.id)
    migrateGroupChatPrefs(fromId = sessionId, toId = session.id)
    // "New Chat in Group": file the just-promoted draft into its folder.
    // Unconditional (vs iOS setFolderIfUnfiled) — the session is seconds
    // old and nothing else can have filed it yet.
    initialFolderId?.let { chatRepository.setFolderForSessions(it, listOf(session.id)) }
    // Move our cached VM from the draft key ("__new__...") to the real
    // sessionId so re-entering the session reuses the same instance.
    if (isDraft) {
        ChatViewModelStore.rename(sessionId, session.id)
        // Bring every disk/shell resource that was opened with the draft
        // id over to the real id *before* agent tools start running against
        // the persisted session — otherwise the first tool call (e.g.
        // yt-dlp writing into /var/minis/attachments) would land in
        // minis-sessions/__new__*/… and be orphaned when the user
        // re-enters the session and everything is resolved via the real
        // id. See debug report 2026-04-21 (TikTok Chinese filename).
        migrateDraftResources(fromDraft = sessionId, toReal = session.id)
        // [T-android-session-skill-override-init-timing] Re-point any
        // session_skill_overrides / mcp_session_overrides rows written
        // pre-first-message (against `__new__<uuid>`) onto the real
        // session id, mirroring the disk-resource hop above. Without
        // this, a skill or MCP server the user toggled on the draft
        // session sheet vanishes the next time the same chat is opened
        // (the prop carries the real id by then, but the override row
        // is still stranded under the draft key). Aligns with iOS
        // ed861471 (T-ios-session-skill-override-init-timing). Cheap
        // no-op when no rows match.
        skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        // Re-point the lazily-created BrowserTabPool if it was already
        // instantiated against the draft key (e.g. user opened the browser
        // sheet before sending a message). Without this, cookies and
        // downloads keep flowing into the draft directory.
        _browserTabPoolRef?.setSession(session.id)
    }
    // Persist the current model binding so it survives re-entry
    val groupId = _selectedGroupId.value
    val entryId = _activeEntryId.value
    val binding = when {
        groupId != null && entryId != null -> """{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"""
        groupId != null -> """{"type":"group","groupId":"$groupId"}"""
        entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
        else -> null
    }
    if (binding != null) {
        chatRepository.updateSessionBinding(realSessionId, binding, modelId)
    }
    return realSessionId
}
