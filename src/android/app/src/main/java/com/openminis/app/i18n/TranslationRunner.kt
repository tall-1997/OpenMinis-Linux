package com.openminis.app.i18n

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of one translation. Never written into a conversation by this type. */
sealed class TranslationOutcome {
    data class Text(val value: String) : TranslationOutcome()
    data class Failed(val message: String) : TranslationOutcome()
}

/**
 * The one translation path shared by the assistant bubble and the standalone
 * page. A saved [TranslationPrefs] entry wins. With no saved entry, the first
 * enabled, non-hidden model is the fallback. A saved id that no longer resolves
 * does not silently switch models.
 */
object TranslationRunner {
    fun resolveEntry(
        entries: List<ModelEntry>,
        instances: List<ProviderInstance>,
        savedId: String?,
    ): ModelEntry? {
        fun usable(id: String): ModelEntry? {
            val entry = entries.find { it.id == id && !it.isHidden } ?: return null
            val inst = instances.find { it.id == entry.providerInstanceId } ?: return null
            return entry.takeIf { inst.isEnabled }
        }
        val saved = savedId?.takeIf { it.isNotBlank() }
        if (saved != null) return usable(saved)
        return entries.firstNotNullOfOrNull { if (it.isHidden) null else usable(it.id) }
    }

    fun resolveEntry(context: Context): ModelEntry? {
        val repo = (context.applicationContext as? MinisApp)?.providerRepository ?: return null
        val config = repo.config.value
        return resolveEntry(config.modelEntries, config.instances, TranslationPrefs.entryId(context))
    }

    suspend fun translate(context: Context, text: String, lang: String): TranslationOutcome {
        val app = context.applicationContext as? MinisApp
            ?: return TranslationOutcome.Failed(context.getString(R.string.translate_no_model))
        val repo = app.providerRepository
        val entry = withContext(Dispatchers.IO) { resolveEntry(context) }
            ?: return TranslationOutcome.Failed(context.getString(R.string.translate_set_in_defaults))
        val instance = repo.instance(entry.providerInstanceId)
            ?: return TranslationOutcome.Failed(context.getString(R.string.translate_no_model))
        val key = repo.usableApiKey(instance)
            ?: return TranslationOutcome.Failed(context.getString(R.string.translate_no_model))
        val target = lang.trim().ifBlank { TranslationPrefs.lang(context) }
        return try {
            val provider = ProviderFactory.create(instance, key, entry.model, context)
            val response = provider.sendMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = text)),
                systemPrompt = "You are a translator. Return only the translation into $target. No preface.",
                maxTokens = 4096,
                temperature = 0.2,
                thinkingLevel = ThinkingLevel.OFF,
            )
            val body = response.text.trim()
            if (body.isBlank()) {
                TranslationOutcome.Failed(context.getString(R.string.translate_empty))
            } else {
                TranslationOutcome.Text(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TranslationOutcome.Failed(e.message ?: context.getString(R.string.translate_empty))
        }
    }
}
