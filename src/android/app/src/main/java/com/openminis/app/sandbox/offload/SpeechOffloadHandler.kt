package com.openminis.app.sandbox.offload

import android.content.Context
import android.speech.SpeechRecognizer
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.speech.RecognizerErrorCodes
import com.openminis.app.speech.RecognizerFallback
import com.openminis.app.speech.RecognizerLocales
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * android-speech — speech recognition (audio → text).
 *
 * T61: re-aligned with iOS `apple-speech` (NativeOffloads/SpeechOffload.m).
 *
 *   android-speech transcribe [--engine system|provider|auto] [--source mic|<path>]
 *                             [--language tag] [--duration N] [--on-device]
 *   android-speech listen [...]              # legacy alias for transcribe
 *   android-speech languages [--language prefix]
 *   android-speech status [--probe]
 *
 * [T-431] (GH #431; the analysis that found these is the issue author's):
 *   - Output uses the iOS envelope `{ok, tool, action, data|error, timestamp}`;
 *     success data is also copied to the top level for prompts that learned
 *     the flat pre-#431 shape. Failures exit non-zero (transcribe used to
 *     print `{"error": ...}` with exit 0).
 *   - `status` says why recognition may not work instead of only whether a
 *     service is listed.
 *   - `languages` asks the recognizer the documented way and fails with
 *     `unavailable` instead of printing an empty list.
 *   - Recognizer errors are actionable ("Client error" → which service, why,
 *     what to try).
 *   - [T-431-fallback] The regular recognizer is the default; `--on-device`
 *     prefers the on-device one. A client / disconnect / language failure
 *     tries the other kind once (rule shared with the chat voice input), and
 *     the result names the recognizer that answered.
 *
 *   - [T-431-engine] `--engine provider|auto` transcribes with the Voice Input
 *     group's speech-to-text model (the chat voice input's provider path),
 *     from the mic or from an audio file. Default stays `system`, so audio
 *     does not silently start going to a third-party service; every provider
 *     result says where it went (rules in [SpeechEngines]).
 *
 * The system recognizer takes the microphone only: Android's [SpeechRecognizer]
 * accepts no audio files on the vendor implementations we ship to, so
 * `--source <path>` needs `--engine provider` (or `auto`).
 *
 * Permission flow: when a transcribe attempt needs RECORD_AUDIO and the user
 * has already permanently denied it, we route through the same 3-stage flow
 * the chat composer's mic button uses (T67).
 */
class SpeechOffloadHandler internal constructor(
    private val system: SpeechSystem,
) : NativeOffloadHandler {

    constructor(context: Context) : this(AndroidSpeechSystem(context))

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), BOOLEAN_FLAGS)
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) SpeechCli.EXIT_INVALID_ARGS else 0, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "status" -> cmdStatus(args)
                "languages" -> cmdLanguages(args)
                "transcribe", "listen" -> cmdTranscribe(args, request.pid)
                else -> emit(
                    args,
                    SpeechCli.EXIT_INVALID_ARGS,
                    SpeechCli.error(
                        sub,
                        "invalid_args",
                        "Unknown command '$sub'. Valid commands: transcribe, languages, status. Use --help for details.",
                    ),
                )
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            emit(args, SpeechCli.EXIT_ERROR, SpeechCli.error("internal", "internal", e.message ?: "unknown"))
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────────

    private fun cmdStatus(args: OffloadArgs): NativeOffloadResult {
        val probe = if (args.hasFlag("probe")) system.probe(PROBE_TIMEOUT_MS) else null
        val env = system.environment()
        val data = SpeechCli.status(env, probe)
            .put("recognizer_order", JSONArray(plan(false, env).map { it.label }))
            .put("recognizer_order_on_device", JSONArray(plan(true, env).map { it.label }))
            .put("engine", SpeechEngines.status(system.voiceInputModels(), env.services.isNotEmpty(), SpeechCli.assess(env).first))
        return emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("status", data))
    }

    private fun plan(preferOnDevice: Boolean, env: SpeechCli.Environment) =
        RecognizerFallback.plan(preferOnDevice, env.sdkInt, env.onDeviceAvailable)

    private fun cmdLanguages(args: OffloadArgs): NativeOffloadResult {
        val details = system.voiceDetails(LANGUAGES_TIMEOUT_MS)
        val support = system.recognitionSupport(LANGUAGES_TIMEOUT_MS)
        val result = SpeechCli.languages(details, support, RecognizerLocales.BUILTIN_TAGS, args.get("language"))
        return emit(args, result.exitCode, result.body)
    }

    private fun cmdTranscribe(args: OffloadArgs, callerPid: Int): NativeOffloadResult {
        // [T-431-cancel] Checked while recording and before any upload.
        val callerGone = { !system.callerAlive(callerPid) }
        val engine = SpeechEngines.parse(args.get("engine")) ?: return emit(
            args,
            SpeechCli.EXIT_INVALID_ARGS,
            SpeechCli.error("transcribe", "invalid_args", "--engine must be system, provider or auto."),
        )
        val env = system.environment()
        // iOS-style flag names: --language, --duration. Pre-T61 callers also
        // passed --max / --timeout; preserve those.
        val language = args.get("language")?.takeIf { it.isNotBlank() }?.let { RecognizerLocales.recognizerTag(it) }
        val maxResults = args.getInt("max") ?: 3
        val durationSec = (args.getInt("duration") ?: args.getInt("timeout") ?: DEFAULT_DURATION_SEC)
            .coerceIn(1, MAX_DURATION_SEC)
        val onDeviceRequested = args.hasFlag("on-device")
        val models = system.voiceInputModels()

        // --source: default mic, accept "mic" / "system-mic" / any /var/minis/... path.
        val source = args.get("source")
        if (source != null && !sourceIsMic(source)) return transcribeFile(args, engine, source, language, models, callerGone)

        if (engine == SpeechEngines.Engine.PROVIDER && models.isEmpty()) return noModel(args)
        val providerFirst = engine == SpeechEngines.Engine.PROVIDER ||
            (engine == SpeechEngines.Engine.AUTO &&
                SpeechEngines.autoStartsWithProvider(env.services.isNotEmpty(), SpeechCli.assess(env).first, models.isNotEmpty()))
        if (!providerFirst && env.services.isEmpty()) {
            return emit(
                args,
                SpeechCli.EXIT_NOT_AVAILABLE,
                SpeechCli.error(
                    "transcribe",
                    "recognizer_unavailable",
                    "No speech recognition service is registered on this device. This is common on " +
                        "devices without Google Mobile Services (Huawei HMS-only, some stripped China " +
                        "ROMs). Ask the user to install a speech recognition engine (e.g. Google, or " +
                        "an OEM-provided alternative). " + providerHint(models),
                ),
            )
        }

        permissionGate(args)?.let { return it }

        return withMic(args) {
            if (providerFirst) {
                val from = if (engine == SpeechEngines.Engine.AUTO) {
                    JSONObject().put("engine", "system").put("reason", SpeechCli.assess(env).second ?: "system recognizer not usable")
                } else {
                    null
                }
                return@withMic providerFromMic(args, engine, durationSec, language, from, callerGone)
            }
            val order = plan(onDeviceRequested, env)
            val failed = mutableListOf<RecognizeOutcome.Failure>()
            var outcome: RecognizeOutcome
            var i = 0
            while (true) {
                val kind = order[i]
                outcome = system.recognize(
                    RecognizeRequest(
                        language, maxResults, durationSec.toLong(),
                        kind == RecognizerFallback.Kind.ON_DEVICE,
                        isCancelled = callerGone,
                    ),
                )
                val next = (outcome as? RecognizeOutcome.Failure)?.let { RecognizerFallback.next(order, i, it.code) }
                if (next == null) break
                AppLogger.info(TAG, "transcribe: ${kind.label} failed (${RecognizerErrorCodes.name((outcome as RecognizeOutcome.Failure).code)}), trying ${next.label}")
                failed += outcome
                i++
            }
            val attempts = Attempts(order, failed, onDeviceRequested)
            if (engine == SpeechEngines.Engine.AUTO && models.isNotEmpty() && SpeechEngines.autoFallsBack(outcome)) {
                // [T-431-engine] The system recognizer could not be reached:
                // record it for `status`, then capture again for the provider.
                val from = JSONObject().put("engine", "system").put("failed_attempts", attempts.json())
                (outcome as? RecognizeOutcome.Failure)?.let {
                    system.recordLastError(it.code, it.recognizer)
                    from.put("error_code", it.code).put("error_name", RecognizerErrorCodes.name(it.code))
                        .put("recognizer", it.recognizer)
                }
                return@withMic providerFromMic(args, engine, durationSec, language, from, callerGone)
            }
            transcribeResult(args, outcome, language, durationSec, env, attempts, listOf(providerHint(models)))
        }
    }

    // ── [T-431-engine] provider engine ───────────────────────────────────────

    /** What the system engine's errors suggest about the provider engine. */
    private fun providerHint(models: List<SpeechEngines.VoiceModel>): String {
        val m = models.firstOrNull()
            ?: return "To transcribe with a model service instead, add a speech-to-text model to " +
                "Settings > Model Groups > Voice Input and use `--engine provider`."
        return "`--engine provider` (or `--engine auto`) transcribes with the Voice Input model instead; " +
            "the audio is then uploaded to ${SpeechEngines.destination(m)}."
    }

    /**
     * [T-431-cancel] The caller is gone, so nobody reads this; it is for the
     * log. The point is what did NOT happen: no further capture, no upload.
     */
    private fun cancelled(args: OffloadArgs, uploaded: Boolean): NativeOffloadResult {
        AppLogger.info(TAG, "transcribe: caller exited - stopped${if (uploaded) "" else ", nothing uploaded"}")
        return emit(
            args,
            SpeechCli.EXIT_CANCELLED,
            SpeechCli.error(
                "transcribe",
                "cancelled",
                "The caller exited; recording stopped" + if (uploaded) "." else " and no audio was uploaded.",
            ),
        )
    }

    private fun noModel(args: OffloadArgs) = emit(
        args,
        SpeechCli.EXIT_NOT_AVAILABLE,
        SpeechCli.error("transcribe", "no_voice_input_model", SpeechEngines.noModelMessage()),
    )

    private fun providerFromMic(
        args: OffloadArgs,
        engine: SpeechEngines.Engine,
        durationSec: Int,
        language: String?,
        fallbackFrom: JSONObject?,
        callerGone: () -> Boolean,
    ): NativeOffloadResult = when (val capture = system.recordMic(durationSec, callerGone)) {
        is CaptureOutcome.Failed -> emit(
            args,
            SpeechCli.EXIT_ERROR,
            SpeechCli.error("transcribe", capture.code, capture.message, JSONObject().put("engine_used", "provider")),
        )
        CaptureOutcome.Cancelled -> cancelled(args, uploaded = false)
        is CaptureOutcome.Ok -> if (callerGone()) cancelled(args, uploaded = false) else providerResult(
            args,
            system.providerTranscribe(capture.wav, language),
            engine,
            language,
            JSONObject().put("source", "mic").put("source_type", "mic").put("duration_seconds", durationSec),
            fallbackFrom,
        )
    }

    private fun transcribeFile(
        args: OffloadArgs,
        engine: SpeechEngines.Engine,
        source: String,
        language: String?,
        models: List<SpeechEngines.VoiceModel>,
        callerGone: () -> Boolean,
    ): NativeOffloadResult {
        val resolved = system.resolveSourcePath(source)
        val file = resolved?.takeIf { it.isFile }
        val exists = file != null
        AppLogger.info(TAG, "transcribe --source=$source engine=${engine.label} exists=$exists")
        val pathInfo = JSONObject()
            .put("requested_path", source)
            .put("resolved_host_path", resolved?.absolutePath ?: "")
            .put("file_exists", exists)
        if (engine == SpeechEngines.Engine.SYSTEM) {
            return emit(
                args,
                SpeechCli.EXIT_INVALID_ARGS,
                SpeechCli.error(
                    "transcribe",
                    "not_supported",
                    "The Android system recognizer only takes the microphone, not audio files. " +
                        "Use `--engine provider` to transcribe the file with the Voice Input model " +
                        "(the audio is uploaded to that service)." +
                        (models.firstOrNull()?.let { " Configured: ${SpeechEngines.destination(it)}." }
                            ?: " No Voice Input model is configured yet: add one in Settings > Model Groups > Voice Input."),
                    pathInfo,
                ),
            )
        }
        if (models.isEmpty()) return noModel(args)
        if (file == null) {
            return emit(
                args,
                SpeechCli.EXIT_INVALID_ARGS,
                SpeechCli.error("transcribe", "invalid_args", "Audio file not found: $source", pathInfo),
            )
        }
        // [T-speech-decode-streaming] The decode stops as soon as the caller is gone.
        val decoded = system.decodeAudioFile(file, callerGone)
        if (callerGone()) return cancelled(args, uploaded = false)
        return when (decoded) {
            is DecodeOutcome.Failed -> emit(
                args,
                if (decoded.code == "file_too_large" || decoded.code == "audio_too_long") SpeechCli.EXIT_INVALID_ARGS else SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    decoded.code,
                    decoded.message + " Supported: WAV up to 25 MB as-is; MP3, M4A/AAC, FLAC, OGG/Opus, AMR, " +
                        "WebM and larger WAV are decoded on the device (max ${SpeechAudio.MAX_FILE_SECONDS / 60} min, " +
                        "${SpeechAudio.MAX_FILE_BYTES / (1024 * 1024)} MB).",
                    pathInfo,
                ),
            )
            is DecodeOutcome.Ok -> if (callerGone()) cancelled(args, uploaded = false) else providerResult(
                args,
                system.providerTranscribe(decoded.wav, language),
                engine,
                language,
                JSONObject()
                    .put("source", source)
                    .put("source_type", "audio_file")
                    .put("duration_seconds", decoded.seconds ?: JSONObject.NULL)
                    .put("file_format", decoded.format.label)
                    .put("decoded_on_device", decoded.decoded),
                null,
            )
        }
    }

    private fun providerResult(
        args: OffloadArgs,
        outcome: ProviderOutcome,
        engine: SpeechEngines.Engine,
        language: String?,
        sourceInfo: JSONObject,
        fallbackFrom: JSONObject?,
    ): NativeOffloadResult = when (outcome) {
        ProviderOutcome.NoModel -> noModel(args)
        is ProviderOutcome.Success -> {
            val data = JSONObject()
                .put("text", outcome.text)
                .put("engine_used", "provider")
                .put("engine_requested", engine.label)
                .put("provider_label", outcome.model.providerLabel)
                .put("model", outcome.model.modelId)
                .put("audio_sent_to", SpeechEngines.destination(outcome.model))
                .put("notice", SpeechEngines.sentNotice(outcome.model))
                .put("locale", language ?: JSONObject.NULL)
            for (key in sourceInfo.keys()) data.put(key, sourceInfo.get(key))
            if (outcome.text.isEmpty()) data.put("no_speech", true)
            if (fallbackFrom != null) data.put("fallback_from", fallbackFrom)
            emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("transcribe", data))
        }
        is ProviderOutcome.Failed -> {
            val sentTo = outcome.errors.map { SpeechEngines.destination(it.first) }
            val extra = JSONObject()
                .put("engine_used", "provider")
                .put("engine_requested", engine.label)
                .put("providers", SpeechEngines.providerErrors(outcome.errors))
                .put("audio_sent_to", JSONArray(sentTo))
            if (fallbackFrom != null) extra.put("fallback_from", fallbackFrom)
            emit(
                args,
                SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    "provider_error",
                    "Every Voice Input model failed. Audio was sent to: ${sentTo.joinToString("; ")}. " +
                        "Last error: ${outcome.errors.lastOrNull()?.second ?: "unknown"}",
                    extra,
                ),
            )
        }
    }

    /** What the recognizer fallback did, for the result. */
    private data class Attempts(
        val order: List<RecognizerFallback.Kind>,
        val failed: List<RecognizeOutcome.Failure>,
        val onDeviceRequested: Boolean,
    ) {
        fun json(): JSONArray = JSONArray().apply {
            failed.forEach {
                put(
                    JSONObject().put("recognizer", it.recognizer)
                        .put("error_code", it.code)
                        .put("error_name", RecognizerErrorCodes.name(it.code)),
                )
            }
        }

        fun annotate(data: JSONObject, env: SpeechCli.Environment): JSONObject {
            data.put("fallback_used", failed.isNotEmpty())
            data.put("on_device_requested", onDeviceRequested)
            if (failed.isNotEmpty()) data.put("failed_attempts", json())
            if (onDeviceRequested && order.first() != RecognizerFallback.Kind.ON_DEVICE) {
                data.put(
                    "note",
                    if (env.sdkInt < 31) {
                        "On-device recognition needs Android 12; used the regular recognizer."
                    } else {
                        "No on-device recognizer is available on this device; used the regular recognizer."
                    },
                )
            }
            return data
        }

        /** What else the caller can try, after everything was tried. */
        fun hints(env: SpeechCli.Environment): List<String> {
            val tried = order.take(failed.size + 1)
            return when {
                tried.size > 1 -> listOf("Both the regular and the on-device recognizer failed.")
                env.onDeviceAvailable != true -> listOf("This device has no on-device recognizer to fall back to.")
                else -> emptyList()
            }
        }
    }

    /**
     * One microphone capture at a time, process-wide: two recognizer sessions
     * fight over the mic and the second one fails or records nothing. The chat
     * voice input owns the mic while it records, as it does for its own taps.
     */
    private fun withMic(args: OffloadArgs, body: () -> NativeOffloadResult): NativeOffloadResult {
        system.micBusyReason()?.let { return busy(args, it) }
        if (!micInUse.compareAndSet(false, true)) {
            return busy(args, "Another android-speech transcribe is already using the microphone.")
        }
        try {
            return body()
        } finally {
            micInUse.set(false)
        }
    }

    private fun busy(args: OffloadArgs, why: String) = emit(
        args,
        SpeechCli.EXIT_ERROR,
        SpeechCli.error("transcribe", "busy", "$why Try again when it has finished."),
    )

    private fun transcribeResult(
        args: OffloadArgs,
        outcome: RecognizeOutcome,
        language: String?,
        durationSec: Int,
        env: SpeechCli.Environment,
        attempts: Attempts,
        extraHints: List<String> = emptyList(),
    ): NativeOffloadResult {
        val locale = language ?: env.locale
        return when (outcome) {
            is RecognizeOutcome.Success -> {
                system.recordLastError(null, null)
                val data = attempts.annotate(successData(outcome, locale, durationSec), env)
                emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("transcribe", data))
            }
            is RecognizeOutcome.Failure -> {
                if (outcome.code == SpeechRecognizer.ERROR_NO_MATCH || outcome.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // The recognizer worked and heard nothing: an empty
                    // result, not a failure (iOS returns its empty text too).
                    system.recordLastError(null, null)
                    val data = attempts.annotate(baseData(locale, durationSec, outcome.recognizer), env)
                        .put("text", "")
                        .put("no_speech", true)
                    return emit(args, SpeechCli.EXIT_OK, SpeechCli.ok("transcribe", data))
                }
                system.recordLastError(outcome.code, outcome.recognizer)
                emit(
                    args,
                    SpeechCli.EXIT_ERROR,
                    SpeechCli.error(
                        "transcribe",
                        SpeechCli.errorCode(outcome.code),
                        SpeechCli.recognizerErrorMessage(outcome.code, language, env.defaultService, attempts.hints(env) + extraHints),
                        JSONObject()
                            .put("android_error", outcome.code)
                            .put("android_error_name", RecognizerErrorCodes.name(outcome.code))
                            .put("recognizer", outcome.recognizer)
                            .put("default_service", env.defaultService ?: JSONObject.NULL)
                            .put("failed_attempts", attempts.json()),
                    ),
                )
            }
            is RecognizeOutcome.Timeout -> emit(
                args,
                SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    "timeout",
                    "Speech recognition timed out after ${outcome.seconds}s without a result.",
                    JSONObject().put("recognizer", outcome.recognizer).put("failed_attempts", attempts.json()),
                ),
            )
            is RecognizeOutcome.Cancelled -> cancelled(args, uploaded = false)
            is RecognizeOutcome.CreationFailed -> emit(
                args,
                SpeechCli.EXIT_ERROR,
                SpeechCli.error(
                    "transcribe",
                    "recognizer_creation_failed",
                    outcome.message,
                    JSONObject().put("recognizer", outcome.recognizer),
                ),
            )
        }
    }

    private fun baseData(locale: String, durationSec: Int, recognizer: String): JSONObject = JSONObject()
        .put("locale", locale)
        .put("source", "mic")
        .put("source_type", "mic")
        .put("duration_seconds", durationSec)
        .put("engine_used", "system")
        .put("recognizer", recognizer)
        .put("on_device", recognizer == Recognizers.ON_DEVICE)

    private fun successData(s: RecognizeOutcome.Success, locale: String, durationSec: Int): JSONObject {
        val data = baseData(locale, durationSec, s.recognizer).put("text", s.texts.firstOrNull() ?: "")
        if (s.texts.size > 1) {
            val alts = JSONArray()
            s.texts.forEachIndexed { i, t ->
                val a = JSONObject().put("text", t)
                s.confidences?.getOrNull(i)?.let { a.put("confidence", String.format(java.util.Locale.US, "%.2f", it)) }
                alts.put(a)
            }
            data.put("alternatives", alts)
        }
        return data
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun permissionGate(args: OffloadArgs): NativeOffloadResult? {
        if (system.hasMicPermission()) return null
        AppLogger.warning(TAG, "RECORD_AUDIO not granted — routing through permission flow")
        return when (system.requestMicPermission()) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> emit(
                args,
                SpeechCli.EXIT_PERMISSION,
                SpeechCli.error("transcribe", "permission_denied", "The user declined the microphone permission."),
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> emit(
                args,
                SpeechCli.EXIT_PERMISSION,
                SpeechCli.error(
                    "transcribe",
                    "timeout",
                    "Timed out waiting for the user to grant the microphone permission.",
                ),
            )
        }
    }

    private fun emit(args: OffloadArgs, exit: Int, body: JSONObject): NativeOffloadResult =
        NativeOffloadResult(exit, OffloadOutput.formatBody(body.toString(2), args) + "\n")

    private fun sourceIsMic(source: String): Boolean {
        val s = source.trim().lowercase()
        return s.isEmpty() || s == "mic" || s == "system-mic" || s == "system_mic" || s == "microphone"
    }

    companion object {
        private const val TAG = "SpeechOffload"
        private const val DEFAULT_DURATION_SEC = 30
        private const val MAX_DURATION_SEC = 60
        private const val PROBE_TIMEOUT_MS = 3_000L
        private const val LANGUAGES_TIMEOUT_MS = 3_000L
        private val BOOLEAN_FLAGS = setOf("h", "help", "probe", "on-device")

        /** Process-wide: one android-speech microphone capture at a time. */
        private val micInUse = AtomicBoolean(false)

        private const val HELP = """android-speech — speech recognition (audio → text)

Usage:
  android-speech <command> [options]

COMMANDS:
  transcribe   Transcribe speech from the microphone or an audio file
  listen       Alias for transcribe (legacy)
  languages    List the recognition locales the system reports
  status       Check speech recognition availability (and why it may fail)

OPTIONS:
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only the data (or error) field
  --engine <name>      system (default): Android's speech recognizer; audio
                       stays with the system recognition service.
                       provider: the speech-to-text model in Settings > Model
                       Groups > Voice Input. Audio is UPLOADED to that service,
                       which may charge per minute.
                       auto: system, but the provider when the system
                       recognizer cannot be reached; the result then says
                       "Audio was sent to provider ..." (audio_sent_to).
  --source <mic|path>  Source: system mic (default) or an audio file path
                       (needs --engine provider or auto). WAV up to 25 MB is
                       sent as-is; MP3, M4A/AAC, FLAC, OGG/Opus, AMR, WebM and
                       larger WAV are decoded to 16 kHz WAV on the device
                       first. Limits: 100 MB file, 10 min audio.
  --duration <sec>     Recording duration for mic source (default: 30, max: 60)
  --language <locale>  Recognition locale (e.g. en-US, zh-CN)
  --max <N>            Maximum number of result candidates (default: 3)
  --on-device          Prefer the on-device recognizer (Android 12+). Default
                       is the regular system recognizer. Either one falls back
                       to the other once on a connection or language error;
                       the result says which one answered (recognizer,
                       fallback_used, failed_attempts).
  --probe              (status) Open and cancel one recognizer session to test
                       the connection. Uses the microphone briefly.

OUTPUT:
  JSON envelope {ok, tool, action, data|error, timestamp}. Success data fields
  are also repeated at the top level. Errors have error.code / error.message.
  Exit codes: 0 ok, 1 error, 2 invalid arguments, 4 not available,
  77 permission denied.

EXAMPLES:
  android-speech transcribe --duration 5
  android-speech transcribe --source mic --language zh-CN --duration 15
  android-speech transcribe --on-device --language en-US
  android-speech transcribe --engine provider --duration 10
  android-speech transcribe --engine provider --source /var/minis/attachments/meeting.m4a
  android-speech transcribe --engine auto --language zh-CN
  android-speech languages
  android-speech languages --language en
  android-speech status
  android-speech status --probe

Notes:
  - Requires RECORD_AUDIO permission for mic transcription.
  - `status` shows the Voice Input model (name and host, never the key) and
    what `--engine auto` would use.
  - `languages` fails with error.code "unavailable" when the system does not
    report its languages; error.fallback then lists Minis Ultra' built-in locales,
    which are not a report from this device.
"""
    }
}
