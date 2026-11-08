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
 * process-level [TranslationStore] (keyed by block id + content hash) so a
 * collapse/re-expand or recomposition never loses translated segments.
 * [dispose] aborts in-flight work; the store drops the controller but keeps
 * its cache entries.
 */
class ReasoningTranslationController(
    private val blockKey: String,
    private val provider: TranslationProvider,
    private val targetLanguage: String,
    private val scope: CoroutineScope,
    private val http: TranslationHttp,
    private val publish: (CotTranslationState) -> Unit,
) {
    /** Display state consumed by the UI: spliced text + progress flags. */
    data class CotTranslationState(val text: String, val pending: Boolean, val failed: Boolean)

    private var disposed = false
    private var pending = false
    private var failed = false
    private val cache = HashMap<String, String>()
    private var job: Job? = null

    /**
     * Adopt the current source text. Complete fragments that miss the cache
     * (and the shared store) are queued for serial translation; the published
     * text splices cached translations with the untranslated original tail.
     */
    fun update(text: String, running: Boolean) {
        if (disposed) return
        val fragments = reasoningFragments(text, running)
        emit(fragments)
        if (pending) return
        val next = fragments.firstOrNull {
            it.complete && it.text.isNotBlank() && !cache.containsKey(it.text) &&
                !TranslationStore.hasCached(blockKey, it.text)
        } ?: return
        pending = true
        emit(fragments)
        job?.cancel()
        job = scope.launch {
            var current = next
            while (true) {
                if (disposed) return@launch
                val result = translateOnce(
                    TranslationRequest(current.text, targetLanguage), provider, http,
                )
                if (disposed) return@launch
                when (result) {
                    is TranslationResult.Ok -> {
                        cache[current.text] = result.text
                        TranslationStore.putCached(blockKey, current.text, result.text)
                        failed = false
                        emit(fragments)
                    }
                    is TranslationResult.Failed -> {
                        failed = true
                        emit(fragments)
                        AppLogger.warning(TAG, "translation failed: ${result.reason}")
                        return@launch
                    }
                }
                current = fragments.firstOrNull {
                    it.complete && it.text.isNotBlank() && !cache.containsKey(it.text) &&
                        !TranslationStore.hasCached(blockKey, it.text)
                } ?: break
            }
            pending = false
            emit(fragments)
        }
    }

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

    private fun emit(fragments: List<Fragment>) {
        if (disposed) return
        val text = fragments.joinToString("") { f ->
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
 * `blockKey` (block.id + content hash) and fragment text. Survives collapse,
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
    ): ReasoningTranslationController {
        controllers[blockKey]?.dispose()
        val c = ReasoningTranslationController(blockKey, provider, targetLanguage, scope, http, publish)
        controllers[blockKey] = c
        return c
    }

    fun dispose(blockKey: String) {
        controllers.remove(blockKey)?.dispose()
    }
}
