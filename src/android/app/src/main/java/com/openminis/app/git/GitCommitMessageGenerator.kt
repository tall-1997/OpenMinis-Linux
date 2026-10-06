package com.openminis.app.git

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.LLMProvider
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/**
 * One-shot commit-message generation from a staged diff.
 *
 * Same provider-resolution shape as [com.openminis.app.evolution.EvolutionLlm]
 * (first enabled, non-hidden model entry with a usable key) — the commit
 * message is a utility completion, not a chat turn, so it deliberately
 * bypasses the agent loop and its tool surface. No SecurityGate involvement:
 * nothing executes, the diff only feeds a prompt.
 */
class GitCommitMessageGenerator(
    private val context: Context,
    private val providerRepository: ProviderRepository,
) {
    suspend fun generate(stagedDiff: String): String? {
        val picked = pickProvider() ?: run {
            AppLogger.warning(TAG, "no usable provider for commit message")
            return null
        }
        val (system, user) = buildPrompt(stagedDiff)
        return try {
            val response = withTimeout(TIMEOUT_MS) {
                picked.provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = user)),
                    systemPrompt = system,
                    maxTokens = MAX_TOKENS,
                    temperature = picked.temperature ?: 0.2,
                    thinkingLevel = ThinkingLevel.OFF,
                )
            }
            sanitize(response.text)?.also {
                AppLogger.info(TAG, "generated commit message (${it.length} chars)")
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "generation failed: ${t.message}")
            null
        }
    }

    private data class Picked(val provider: LLMProvider, val temperature: Double?)

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
        private const val TAG = "GitCommitMsg"
        private const val TIMEOUT_MS = 45_000L
        private const val MAX_TOKENS = 400
        private const val DIFF_CAP = 24_000

        internal fun buildPrompt(stagedDiff: String): Pair<String, String> {
            val system = """
You generate Git commit messages. Rules:
- Output ONLY the commit message. No code fences, no preamble, no explanations.
- First line: imperative mood, ≤ 72 chars, no trailing period.
- Use a Conventional Commits type prefix (feat/fix/docs/refactor/test/chore/perf) when one clearly fits; omit it for mixed changesets.
- If the diff is large or multi-topic, add a blank line then a short body of ≤ 4 bullet points.
- Match the dominant language of the changed content's comments/docs when choosing the message language; default to English.
""".trimIndent()
            val capped = if (stagedDiff.length > DIFF_CAP) {
                stagedDiff.take(DIFF_CAP) +
                    "\n…[diff truncated at $DIFF_CAP chars]"
            } else stagedDiff
            return system to "Write a commit message for this staged diff:\n\n$capped"
        }

        /** Strip wrapping code fences / a leading label and trim to ≤ 20 lines. */
        internal fun sanitize(raw: String?): String? {
            var text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            // Models occasionally fence the message despite instructions.
            if (text.startsWith("```")) {
                text = text.removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                text = text.replace(Regex("^[a-zA-Z]+\n"), "").trim()
            }
            text = text.replace(
                Regex("^(commit message|message)\\s*[:：]\\s*", RegexOption.IGNORE_CASE),
                "",
            ).trim()
            if (text.isEmpty()) return null
            val lines = text.lineSequence().take(20).toList()
            return lines.joinToString("\n").trim()
        }

        /** Head+tail cap for the diff shown in the prompt (kept for callers). */
        internal fun capDiff(diff: String): String =
            diff.take(min(diff.length, DIFF_CAP))
    }
}
