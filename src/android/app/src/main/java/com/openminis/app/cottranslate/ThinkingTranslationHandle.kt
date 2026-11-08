package com.openminis.app.cottranslate

import kotlinx.coroutines.CoroutineScope

/**
 * [T-cot-translate] Reasoning-translation UI integration for a thinking block.
 *
 * Holds the [ReasoningTranslationController] for one block (process-level, via
 * [TranslationStore]) plus the local display toggle. The caller supplies the
 * block key (block.id + content hash), the source text, and a publish lambda
 * that feeds the Compose state.
 */
class ThinkingTranslationHandle(
    val blockKey: String,
    val enabled: Boolean,
    val provider: TranslationProvider,
    val targetLanguage: String,
    val scope: CoroutineScope,
    private val http: TranslationHttp,
) {
    var showTranslated: Boolean = false
        private set

    private var controller: ReasoningTranslationController? = null

    /**
     * User tapped the "译" toggle. Starts (or restarts, after a provider or
     * language change) the translation pipeline for this block's CURRENT text.
     * No-op when the feature is disabled.
     */
    fun toggle(context: android.content.Context, text: String, running: Boolean) {
        if (!enabled) return
        showTranslated = !showTranslated
        if (showTranslated) {
            CotTranslationPrefs.setPrivacyAcknowledged(context)
            controller = TranslationStore.controllerFor(
                blockKey, provider, targetLanguage, scope, http,
            ) { state -> onState(state) }
            controller?.update(text, running)
        } else {
            TranslationStore.dispose(blockKey)
            onState(ReasoningTranslationController.CotTranslationState(text, pending = false, failed = false))
        }
    }

    /** Feed the current source text while streaming (complete paragraphs only). */
    fun update(text: String, running: Boolean) {
        if (!showTranslated) return
        controller?.update(text, running)
    }

    /** Retry after a failure. */
    fun retry(text: String, running: Boolean) {
        if (!showTranslated) return
        controller?.retry(text, running)
    }

    /** Collapse/dispose: abort in-flight work; cache entries survive in the store. */
    fun dispose() {
        TranslationStore.dispose(blockKey)
    }

    private var onState: (ReasoningTranslationController.CotTranslationState) -> Unit = {}

    /** Registers the publish callback (called once during UI setup). */
    fun onState(listener: (ReasoningTranslationController.CotTranslationState) -> Unit): ThinkingTranslationHandle {
        onState = listener
        return this
    }

    companion object {
        /** [T-oauth-clock-skew] Anonymous endpoints allow a 10s request budget. */
        const val HTTP_TIMEOUT_MS = 10_000
    }
}
