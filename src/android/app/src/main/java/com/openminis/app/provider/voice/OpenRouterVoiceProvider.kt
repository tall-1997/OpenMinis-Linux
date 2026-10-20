package com.openminis.app.provider.voice

import com.openminis.app.data.model.hasAudioOutput
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

// -- OpenRouter (TTS + ASR, both ride on /v1/chat/completions) ----------------

/**
 * OpenRouter voice — Android port of iOS `OpenRouterVoiceProvider`
 * (iOS 1946b0b2 for TTS, 1f23fd02 for ASR).
 *
 * OpenRouter does not implement OpenAI's dedicated TTS endpoint at all:
 * `POST /api/v1/audio/speech` answers `{"error":{"message":"Model <id> does
 * not exist","code":400}}` for EVERY model id, including `openai/tts-1`. That
 * 400 — the endpoint being absent, not a missing model — is what users saw for
 * every OpenRouter voice entry from fish-audio to gpt-audio.
 *
 * ASR is split across TWO endpoints serving DISJOINT model sets:
 *   - `/v1/audio/transcriptions` takes dedicated ASR models only
 *     (`openai/whisper-1` works; a chat model there returns the same 400);
 *   - `/v1/chat/completions` with an `input_audio` part takes audio-capable
 *     CHAT models (gemini-3.6-flash, gpt-audio-mini), and explicitly REJECTS
 *     whisper ("is a transcription model and cannot be used with the
 *     chat/completions endpoint").
 *
 * So neither half can be routed by provider type alone — the split is per
 * model, and OpenRouter's own error text is the specification.
 */
class OpenRouterVoiceProvider(providerId: String, baseURL: String, apiKey: String?) :
    VoiceProvider(providerId, baseURL, apiKey) {

    /** Where a TTS request goes. */
    enum class TtsRoute { SPEECH_ENDPOINT, CHAT_AUDIO, UNSUPPORTED }

    companion object {
        /**
         * OpenAI's audio-preview models emit 24 kHz mono PCM16 and OpenRouter
         * proxies that stream unchanged. No response field announces the rate,
         * so it is fixed here — a wrong value plays at the wrong pitch, which
         * is immediately audible if it ever changes.
         */
        private const val PCM_SAMPLE_RATE = 24000

        /**
         * The voices OpenAI's audio-preview models accept, as returned verbatim
         * in their own 400 ("Supported values are: …"). Used only to RECOGNISE
         * a valid name on the CHAT-AUDIO route, never to pick one — and never
         * applied to dedicated speech models, whose voices are their own.
         */
        private val KNOWN_VOICES = setOf(
            "alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral",
            "verse", "ballad", "ash", "sage", "marin", "cedar",
        )

        /**
         * Response-body phrases OpenRouter uses when the credential itself is
         * rejected. Only these make a 401/403 an auth error; see [isAuthFailure].
         */
        private val AUTH_FAILURE_PHRASES = listOf(
            "user not found", "no auth credentials", "invalid api key", "invalid_api_key",
        )

        /**
         * FALLBACK ONLY, for models with no catalog tag (custom entries, models
         * saved before OpenMinis#280): ids that look like transcription models.
         * Tagged models route by voiceRole — 12 of the 22 live transcription
         * models (qwen3-asr, voxtral, parakeet, chirp-3, …) contain none of
         * these words.
         *
         * Deepgram is matched VENDOR-qualified, never by the bare engine name:
         * a bare "nova-2"/"nova-3" also matches `amazon/nova-2-lite-v1`, a chat
         * model that would then be misrouted.
         */
        fun isDedicatedTranscriptionModel(modelId: String): Boolean {
            val id = modelId.lowercase(Locale.ROOT)
            return id.contains("whisper") ||
                id.contains("transcribe") || // gpt-4o[-mini]-transcribe
                id.contains("deepgram/")
        }

        /** Pre-#280 name rule for chat-audio TTS, kept for untagged models. */
        private fun looksLikeChatAudio(modelId: String): Boolean {
            val id = modelId.lowercase(Locale.ROOT)
            if (!id.contains("audio")) return false
            return id.contains("gpt") || id.contains("openai")
        }

        /**
         * [T-openrouter-voice-catalog] Pick the TTS route. Tagged models go by
         * tag: "tts" → speech endpoint, the chat-audio allowlist → chat, any
         * other tag (a chat model such as Lyria, an ASR model) → UNSUPPORTED,
         * raised as a clear error instead of a request that can only fail.
         * Untagged models keep the old name rule for chat-audio. Otherwise they
         * go to the speech endpoint when they declare audio output (they passed
         * the picker's gate) or their name says TTS.
         */
        fun ttsRoute(modelId: String, model: com.openminis.app.data.model.LLMModel?): TtsRoute {
            if (modelId in com.openminis.app.data.model.VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS) {
                return TtsRoute.CHAT_AUDIO
            }
            when (model?.voiceRole) {
                com.openminis.app.data.model.VoiceRole.TTS -> return TtsRoute.SPEECH_ENDPOINT
                null -> Unit
                else -> return TtsRoute.UNSUPPORTED
            }
            if (looksLikeChatAudio(modelId)) return TtsRoute.CHAT_AUDIO
            val declaresAudioOut = model?.hasAudioOutput == true
            val nameSaysTts = com.openminis.app.data.model.VoiceModality
                .inferDedicatedVoiceModality(modelId, model?.displayName ?: modelId)
                ?.second == listOf("audio")
            return if (declaresAudioOut || nameSaysTts) TtsRoute.SPEECH_ENDPOINT else TtsRoute.UNSUPPORTED
        }

        /**
         * [T-openrouter-voice-catalog] ASR route: true = chat/completions. A
         * tagged model goes by tag ("stt" → transcriptions endpoint, a chat
         * model → chat); the name rule is only the fallback for untagged ids.
         */
        fun routesAsrThroughChat(model: com.openminis.app.data.model.LLMModel): Boolean {
            if (model.id in com.openminis.app.data.model.VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS) return true
            model.voiceRole?.let { return it != com.openminis.app.data.model.VoiceRole.STT }
            return !isDedicatedTranscriptionModel(model.id)
        }

        /**
         * `voice` for the speech endpoint: forwarded as-is, because each
         * vendor's voices are its own (fish-audio reference ids, minimax
         * voice_ids, kokoro names) and OpenAI's list says nothing about them.
         * Omitted when empty, or when it is just the model id — Quick Test and
         * read-aloud pass the entry id as the voice (the convention for vendors
         * whose catalog entries ARE voices), and a model id is never a voice.
         */
        fun speechVoice(requested: String?, modelId: String): String? =
            requested?.trim()?.takeIf { it.isNotEmpty() && it != modelId }

        /** True only when the body says the credential was rejected. */
        fun isAuthFailureBody(code: Int, body: ByteArray?): Boolean {
            if (code != 401 && code != 403) return false
            val text = body?.toString(Charsets.UTF_8)?.lowercase(Locale.ROOT) ?: return false
            return AUTH_FAILURE_PHRASES.any { text.contains(it) }
        }
    }

    /**
     * [T-openrouter-voice-catalog] OpenMinis#280. A 401 on OpenRouter is not
     * always about the key: sending a model to a route it is not served on
     * also came back 401, and reporting that as "check the API key" sent users
     * hunting for a credential problem that did not exist. Only a body that
     * actually says so is an auth error; anything else keeps its status and
     * OpenRouter's own message ([VoiceProviderException.Http]).
     */
    override fun isAuthFailure(code: Int, body: ByteArray?): Boolean = isAuthFailureBody(code, body)

    /** Same attribution headers the models request already sends. */
    override fun applyVoiceAuth(builder: Request.Builder) {
        super.applyVoiceAuth(builder)
        builder.header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
        builder.header("X-Title", "Minis App")
    }

    // -- ASR ------------------------------------------------------------------

    override fun usesChatBasedASR(model: com.openminis.app.data.model.LLMModel): Boolean =
        routesAsrThroughChat(model)

    // -- TTS ------------------------------------------------------------------

    /**
     * Pick the `audio.voice` value for the CHAT-AUDIO route.
     *
     * Callers disagree on what [VoiceOutputRequest.voice] means: for vendors
     * where a catalog entry IS a voice (ElevenLabs voice_id, Doubao speaker)
     * the entry id is passed straight through, and Quick Test follows that
     * convention. For chat-audio the model and the voice are separate axes, so
     * that convention arrives as `voice: "openai/gpt-audio"` and the upstream
     * answers "Invalid value: 'openai/gpt-audio'. Supported values are:
     * 'alloy', …" — a second 400 hiding behind the first.
     *
     * So: honour a voice the vendor actually knows, otherwise fall back to the
     * default rather than forwarding something certain to fail.
     */
    private fun resolvedVoice(requested: String?, modelId: String): String {
        val want = requested?.takeIf { it.isNotEmpty() } ?: return defaultVoiceOutputVoice()
        val lowered = want.lowercase(Locale.ROOT)
        if (KNOWN_VOICES.contains(lowered)) return lowered
        Log.i(
            "VoiceProvider",
            "OpenRouter chat-audio: ignoring non-voice '$want' for $modelId, using ${defaultVoiceOutputVoice()}",
        )
        return defaultVoiceOutputVoice()
    }

    /** `POST /v1/audio/speech` for a dedicated speech model. */
    override fun buildVoiceOutputRequest(request: VoiceOutputRequest): Request {
        val modelId = request.model ?: defaultVoiceOutputModel()
        val body = JSONObject().apply {
            put("model", modelId)
            put("input", request.input)
            speechVoice(request.voice, modelId)?.let { put("voice", it) }
            put("response_format", request.responseFormat.wireValue)
            request.speed?.let { put("speed", it.toDouble()) }
        }
        val builder = Request.Builder()
            .url(composedUrlString(voiceOutputEndpointPath()))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)
        return builder.build()
    }

    /**
     * TTS dispatch by [ttsRoute]. The chat-audio route is
     * `POST /v1/chat/completions` in the audio-preview shape, with three hard
     * constraints (each verified against the live API):
     *   - `modalities: ["text","audio"]` + `audio: {voice, format}`;
     *   - `stream: true` is MANDATORY — otherwise "Audio output requires
     *     stream: true";
     *   - while streaming, `audio.format` accepts ONLY `pcm16` — `wav` returns
     *     "does not support 'wav' when stream=true".
     *
     * The SSE body is an ordinary chat-completions stream with two extra fields
     * per delta: `audio.data` (base64 PCM16 chunk) and `audio.transcript`. We
     * accumulate the PCM and wrap it in a WAV container, because the caller
     * feeds this to MediaPlayer/ExoPlayer and headerless PCM is not openable.
     */
    override suspend fun synthesize(request: VoiceOutputRequest): ByteArray {
        val modelId = request.model ?: defaultVoiceOutputModel()
        when (ttsRoute(modelId, request.resolvedModel)) {
            TtsRoute.SPEECH_ENDPOINT -> return executeRequest(buildVoiceOutputRequest(request))
            TtsRoute.UNSUPPORTED -> {
                Log.w("VoiceProvider", "OpenRouter TTS: $modelId is not a speech model (voiceRole=${request.resolvedModel?.voiceRole})")
                throw VoiceProviderException.Unsupported(
                    "$modelId does not support speech synthesis on OpenRouter. Pick a text-to-speech model.",
                )
            }
            TtsRoute.CHAT_AUDIO -> Unit
        }

        val url = composedUrlString("/v1/chat/completions")
        val body = JSONObject().apply {
            put("model", modelId)
            put("modalities", JSONArray().put("text").put("audio"))
            put(
                "audio",
                JSONObject()
                    .put("voice", resolvedVoice(request.voice, modelId))
                    // pcm16 is the ONLY value accepted while streaming, and
                    // streaming is mandatory — so this is fixed, not a choice.
                    .put("format", "pcm16"),
            )
            put("stream", true)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", request.input),
                ),
            )
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyVoiceAuth(builder)

        val pcm = ByteArrayOutputStream()
        val transcript = StringBuilder()
        withContext(Dispatchers.IO) {
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    // Drain the body so the server's message survives into the
                    // error — an opaque "HTTP 400" is what made this bug hard
                    // to place in the first place.
                    val errBody = response.body?.bytes()
                    if (isAuthFailure(response.code, errBody)) {
                        Log.e("VoiceProvider", "OpenRouter voice auth failed: HTTP ${response.code}")
                        throw VoiceProviderException.Auth()
                    }
                    Log.e(
                        "VoiceProvider",
                        "OpenRouter chat-audio failed: HTTP ${response.code} " +
                            "body=${errBody?.toString(Charsets.UTF_8).orEmpty()}",
                    )
                    throw VoiceProviderException.Http(response.code, errBody)
                }
                val source = response.body?.source()
                    ?: throw VoiceProviderException.Parse("OpenRouter returned an empty body")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val choices = obj.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val audio = choices.optJSONObject(i)
                            ?.optJSONObject("delta")
                            ?.optJSONObject("audio")
                            ?: continue
                        audio.optString("data").takeIf { it.isNotEmpty() }?.let { b64 ->
                            runCatching { Base64.decode(b64, Base64.DEFAULT) }
                                .getOrNull()?.let { pcm.write(it) }
                        }
                        audio.optString("transcript").takeIf { it.isNotEmpty() }
                            ?.let { transcript.append(it) }
                    }
                }
            }
        }

        val pcmBytes = pcm.toByteArray()
        if (pcmBytes.isEmpty()) {
            throw VoiceProviderException.Parse(
                "OpenRouter returned no audio data for $modelId" +
                    if (transcript.isEmpty()) "" else " (transcript: ${transcript.take(80)})",
            )
        }
        Log.i(
            "VoiceProvider",
            "OpenRouter chat-audio ok model=$modelId pcmBytes=${pcmBytes.size} " +
                "transcriptChars=${transcript.length}",
        )
        return wrapPcm16InWav(pcmBytes, PCM_SAMPLE_RATE)
    }
}
