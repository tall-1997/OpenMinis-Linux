package com.openminis.app.evolution

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.withTimeout

class EvolutionLlm(
    private val context: Context,
    private val providerRepository: ProviderRepository,
    private val prefs: EvolutionPrefs,
) {

    suspend fun complete(system: String, user: String, maxTokens: Int = 400): String? {
        if (prefs.llmFused()) {
            AppLogger.warning(TAG, "LLM fused after repeated failures")
            return null
        }
        val picked = pickProvider() ?: run {
            AppLogger.warning(TAG, "no usable provider")
            return null
        }
        if (!prefs.tryConsumeLlmCall()) {
            AppLogger.info(TAG, "daily LLM cap reached")
            return null
        }
        return try {
            val response = com.openminis.app.data.body.Admission.occupy(user.toByteArray().size.toLong()) { withTimeout(45_000) {
                picked.provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = user)),
                    systemPrompt = system,
                    maxTokens = maxTokens,
                    temperature = picked.temperature,
                    thinkingLevel = ThinkingLevel.OFF,
                )
            } }
            prefs.recordLlmSuccess()
            response.text.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            prefs.recordLlmFailure()
            AppLogger.warning(TAG, "complete failed: ${t.message}")
            null
        }
    }

    private data class Picked(
        val provider: com.openminis.app.provider.LLMProvider,
        val temperature: Double?,
    )

    private fun pickProvider(): Picked? {
        val cfg = providerRepository.config.value
        val enabled = cfg.instances.filter { it.isEnabled }.associateBy { it.id }
        val entry = cfg.modelEntries.firstOrNull { e ->
            !e.isHidden && enabled.containsKey(e.providerInstanceId)
        } ?: return null
        val instance = enabled[entry.providerInstanceId] ?: return null
        val key = providerRepository.usableApiKey(instance) ?: return null
        val provider = runCatching {
            ProviderFactory.create(instance, key, entry.model, context)
        }.getOrNull() ?: return null
        return Picked(provider, entry.overrides.temperature)
    }

    companion object {
        private const val TAG = "EvolutionLlm"
    }
}
