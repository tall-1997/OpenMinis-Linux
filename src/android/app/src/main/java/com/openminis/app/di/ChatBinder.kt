package com.openminis.app.di

/**
 * [Wave3 #4/#16] Read-only view of the live chat/session permission state.
 *
 * `config` (ConfigBuiltins — the minis-config surface) used to import
 * `ui.chat.ChatViewModelStore` directly (an architecture-policy
 * forbiddenImports violation: config is a non-ui layer) and to compose the
 * effective permission mode out of three separate singletons
 * (`ChatViewModelStore.activeSessionId`, `SecurityGateHolder
 * .activeSessionMode`, `ApprovalGate.isSessionAllowAll`). The composition is
 * now the IMPLEMENTATION's job — one facade reads the real values from those
 * three sources — and config only sees this interface, installed through
 * [AppGraph] like every other cross-layer slot.
 *
 * Degradation contract for a missing install (unit tests, pre-wiring):
 * callers see `null` / `false`, which mirrors the old
 * `ChatViewModelStore.activeSessionId == null` "no foregrounded chat"
 * semantics and the ASK-default of `activeSessionMode(null)`.
 *
 * Naming note: the slot is `chatStateBinder` because AppGraph already had a
 * `chatBinder` slot ([ChatSessionBinder], the debug/headless RPC bridge) and
 * two same-named accessors with different types would read as an overload of
 * one concept when they are two.
 */
interface ChatBinder {
    /** The session ChatScreen is composed with right now, or null. */
    fun activeSessionId(): String?

    /**
     * EFFECTIVE tool-gate mode for [id] — the per-session stored mode already
     * composed with that session's allow-all switch ("本会话全部允许").
     * One of [com.openminis.app.security.PermissionMode].name.
     */
    fun sessionMode(id: String?): String?

    /** Whether session [id] has the allow-all (YOYO) switch on. */
    fun sessionAllowAll(id: String?): Boolean
}
