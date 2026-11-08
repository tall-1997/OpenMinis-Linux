package com.openminis.app.cottranslate

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-cot-translate] Per-thinking-block translation controller: owns fragment
 * splitting, serial translation requests, the content-keyed cache, and the
 * published display state (translated-prefix + untranslated original tail).
 *
 * Lifecycle: created lazily on the block's first "译" toggle, held by the
 * process-level [TranslationStore] (keyed by block id) so a collapse/re-expand
 * or recomposition never loses translated segments. [dispose] aborts in-flight
 * work; the store drops the controller but keeps its cache entries.
 *
 * [T-cot-translate-queue] The fragment list is re-derived on every [update]
 * and the running job always reads the NEWEST split when picking the next
 * candidate, so paragraphs that arrive while a request is in flight are queued
 * instead of dropped, and the final publish never regresses to an older
 * snapshot.
 */
class ReasoningTranslationController(
    private val blockKey: String,
    private val provider: TranslationProvider,
    private val targetLanguage: String,
    private val scope: CoroutineScope,
    private val http: TranslationHttp,
    private val publish: (CotTranslationState) -> Unit,
    /** [T-cot-translate-llm] LLM route: set when provider==LLM. Receives the
     *  fragment text, returns the translation (or throws → Failed). */
    private val llmTranslate: (suspend (String) -> String)? = null,
) {
    /** Display state consumed by the UI: spliced text + progress flags. */
    data class CotTranslationState(val text: String, val pending: Boolean, val failed: Boolean)

    private var disposed = false
    private var pending = false
    private var failed = false
    private val cache = HashMap<String, String>()
    private var job: Job? = null

    /** Newest fragment split; the running job re-reads this so fragments
     *  arriving mid-flight are never lost. */
    private var latestFragments: List<Fragment> = emptyList()

    /**
     * Adopt the current source text. Complete fragments that miss the cache
     * (and the shared store) are queued for serial translation; the published
     * text splices cached translations with the untranslated original tail.
     * While a request is in flight the newest split is still published and
     * remembered, so the display tracks the stream and the job picks up every
     * paragraph that completed meanwhile.
     */
    fun update(text: String, running: Boolean) {
        if (disposed) return
        latestFragments = reasoningFragments(text, running)
        emit()
        if (pending || failed) return
        startNextJob()
    }

    private fun startNextJob() {
        val next = latestFragments.firstOrNull {
            it.complete && it.text.isNotBlank() && !cache.containsKey(it.text) &&
                !TranslationStore.hasCached(blockKey, it.text)
        } ?: return
        pending = true
        emit()
        job?.cancel()
        job = scope.launch {
            var current = next
            while (true) {
                if (disposed) return@launch
                val route = llmTranslate
                val result = if (provider == TranslationProvider.LLM && route != null) {
                    try {
                        TranslationResult.Ok(route(current.text))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TranslationResult.Failed(e.message ?: e.javaClass.simpleName)
                    }
                } else {
                    translateOnce(
                        TranslationRequest(current.text, targetLanguage), provider, http,
                    )
                }
                if (disposed) return@launch
                when (result) {
                    is TranslationResult.Ok -> {
                        cache[current.text] = result.text
                        TranslationStore.putCached(blockKey, current.text, result.text)
                        failed = false
                        emit()
                    }
                    is TranslationResult.Failed -> {
                        failed = true
                        pending = false
                        emit()
                        AppLogger.warning(TAG, "translation failed: ${result.reason}")
                        return@launch
                    }
                }
                current = latestFragments.firstOrNull {
                    it.complete && it.text.isNotBlank() && !cache.containsKey(it.text) &&
                        !TranslationStore.hasCached(blockKey, it.text)
                } ?: break
            }
            pending = false
            emit()
        }
    }

    /** Explicit retry after a failure — clears the failed latch, re-splits the
     *  current text and resumes where the cache left off. */
    fun retry(text: String, running: Boolean) {
        failed = false
        pending = false
        update(text, running)
    }

    fun dispose() {
        disposed = true
        job?.cancel()
        job = null
    }

    private fun emit() {
        if (disposed) return
        val text = latestFragments.joinToString("") { f ->
            val content = if (f.complete) cache[f.text] ?: TranslationStore.cached(blockKey, f.text) ?: f.text else f.text
            content + f.suffix
        }
        publish(CotTranslationState(text, pending, failed))
    }

    private companion object {
        val TAG = "CotTranslate"
    }
}

/**
 * [T-cot-translate-store] Process-level cache + controller registry, keyed by
 * `blockKey` (stable block id) and fragment text. Survives collapse,
 * recomposition and navigation within a process; a controller that is dropped
 * keeps its cache entries so a later re-enable resumes instantly.
 */
object TranslationStore {
    private val controllers = HashMap<String, ReasoningTranslationController>()
    private val fragmentCache = HashMap<String, HashMap<String, String>>()

    fun hasCached(blockKey: String, fragment: String): Boolean =
        fragmentCache[blockKey]?.containsKey(fragment) == true

    fun cached(blockKey: String, fragment: String): String? =
        fragmentCache[blockKey]?.get(fragment)

    fun putCached(blockKey: String, fragment: String, translated: String) {
        fragmentCache.getOrPut(blockKey) { HashMap() }[fragment] = translated
    }

    fun controllerFor(
        blockKey: String,
        provider: TranslationProvider,
        targetLanguage: String,
        scope: CoroutineScope,
        http: TranslationHttp,
        publish: (ReasoningTranslationController.CotTranslationState) -> Unit,
        llmTranslate: (suspend (String) -> String)? = null,
    ): ReasoningTranslationController {
        controllers[blockKey]?.dispose()
        val c = ReasoningTranslationController(blockKey, provider, targetLanguage, scope, http, publish, llmTranslate)
        controllers[blockKey] = c
        return c
    }

    fun dispose(blockKey: String) {
        controllers.remove(blockKey)?.dispose()
    }
}
