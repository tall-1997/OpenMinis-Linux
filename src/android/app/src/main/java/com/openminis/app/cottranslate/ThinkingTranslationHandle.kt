package com.openminis.app.cottranslate

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope

/**
 * [T-cot-translate] Reasoning-translation UI integration for a thinking block.
 *
 * Holds the [ReasoningTranslationController] for one block (process-level, via
 * [TranslationStore]) plus the local display toggle. The caller supplies the
 * stable block key (block id), the source text, and a publish lambda that
 * feeds the Compose state.
 *
 * [T-cot-translate-fresh] Every toggle re-reads the live preferences and
 * accepts the freshest provider instance from the UI, so provider / language
 * changes and chat-VM rebuilds (key rotation) apply on the next tap without
 * waiting for the handle to be recreated.
 */
class ThinkingTranslationHandle(
    val blockKey: String,
    val scope: CoroutineScope,
    private val http: TranslationHttp,
) {
    var showTranslated: Boolean = false
        private set

    private var controller: ReasoningTranslationController? = null

    /**
     * User tapped the "译" toggle. Starts (or restarts, after a provider or
     * language change) the translation pipeline for this block's CURRENT text.
     * Returns the new display state so the UI never desyncs; false when the
     * feature is disabled (no state change).
     */
    fun toggle(
        context: Context,
        text: String,
        running: Boolean,
        llmProvider: LLMProvider?,
        llmModelName: String,
    ): Boolean {
        if (!CotTranslationPrefs.enabled(context)) return false
        showTranslated = !showTranslated
        if (showTranslated) {
            CotTranslationPrefs.setPrivacyAcknowledged(context)
            val provider = CotTranslationPrefs.provider(context)
            // [T-cot-translate-llm-fallback] LLM without a live provider
            // instance (sub-agent bar, free lane) degrades to the anonymous
            // Bing route instead of failing every fragment with an internal
            // routing error.
            val useLlm = provider == TranslationProvider.LLM && llmProvider != null
            val effectiveProvider = if (provider == TranslationProvider.LLM && !useLlm) {
                AppLogger.info(TAG, "LLM translate route unavailable — falling back to BING")
                TranslationProvider.BING
            } else {
                provider
            }
            val targetLanguage = resolveTargetLanguage(
                CotTranslationPrefs.targetLanguage(context), Locale.getDefault().language,
            )
            val llmRoute: (suspend (String) -> String)? = llmProvider
                ?.takeIf { useLlm }
                ?.let { lp ->
                    val mn = llmModelName
                    // [T-cot-translate-llm] Bound per-toggle so the freshest
                    // provider instance is used (the chat VM rebuilds on key
                    // rotation). Throw on failure — matches the controller's
                    // route contract (it converts to Failed).
                    { fragment ->
                        when (val r = LlmTranslationRoute.translate(lp, fragment, targetLanguage, mn)) {
                            is TranslationResult.Ok -> r.text
                            is TranslationResult.Failed -> throw IllegalStateException(r.reason)
                        }
                    }
                }
            controller = TranslationStore.controllerFor(
                blockKey, effectiveProvider, targetLanguage, scope, http,
                publish = { state -> onState(state) },
                llmTranslate = llmRoute,
            )
            controller?.update(text, running)
        } else {
            TranslationStore.dispose(blockKey)
            onState(ReasoningTranslationController.CotTranslationState(text, pending = false, failed = false))
        }
        return showTranslated
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
        private const val TAG = "CotTranslateHandle"
    }
}
