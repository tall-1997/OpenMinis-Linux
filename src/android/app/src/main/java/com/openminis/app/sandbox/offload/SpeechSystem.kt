package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.speech.RecognizerErrorCodes
import com.openminis.app.speech.RecognizerLocales
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-431] Everything `android-speech` asks of the system, behind one seam so
 * the handler's decisions (envelope, exit codes, wording) are unit-tested
 * against a fake.
 */
internal interface SpeechSystem {
    fun environment(): SpeechCli.Environment
    fun voiceDetails(timeoutMs: Long): SpeechCli.VoiceDetails
    fun recognitionSupport(timeoutMs: Long): SpeechCli.Support?
    fun hasMicPermission(): Boolean
    fun requestMicPermission(): OffloadPermissionManager.AndroidPermissionResult
    fun recognize(request: RecognizeRequest): RecognizeOutcome
    /** Open and cancel one session, to see whether Android connects at all. */
    fun probe(timeoutMs: Long): JSONObject
    fun resolveSourcePath(source: String): File?
    /** Non-null while the chat voice input is capturing (it owns the microphone). */
    fun micBusyReason(): String?

    /**
     * [T-431-cancel] Whether the guest process that invoked the CLI is still
     * running. The offload protocol has no cancel message: when the caller is
     * killed (Ctrl-C, the agent's stop) the host handler keeps going unless it
     * looks. Offload callers are host processes of this app's uid, so
     * `/proc/<pid>` answers it; pid <= 0 (debug RPC) counts as alive.
     */
    fun callerAlive(pid: Int): Boolean

    // [T-431-engine] Provider engine: the Voice Input group's models.
    fun voiceInputModels(): List<SpeechEngines.VoiceModel>
    fun recordMic(seconds: Int, isCancelled: () -> Boolean = { false }): CaptureOutcome
    fun decodeAudioFile(file: File, isCancelled: () -> Boolean = { false }): DecodeOutcome
    fun providerTranscribe(wav: ByteArray, language: String?): ProviderOutcome
    /** Remember (or, with null, forget) the last recognizer failure for `status`. */
    fun recordLastError(code: Int?, recognizer: String?)
}

internal data class RecognizeRequest(
    val language: String?,
    val maxResults: Int,
    val durationSec: Long,
    /** Which system recognizer this one session uses; fallback is the caller's. */
    val onDevice: Boolean = false,
    /** [T-431-cancel] Polled while waiting; true ends the session at once. */
    val isCancelled: () -> Boolean = { false },
)

internal sealed class RecognizeOutcome {
    abstract val recognizer: String

    data class Success(
        val texts: List<String>,
        val confidences: List<Float>?,
        override val recognizer: String,
    ) : RecognizeOutcome()

    data class Failure(val code: Int, override val recognizer: String) : RecognizeOutcome()

    data class Timeout(val seconds: Long, override val recognizer: String) : RecognizeOutcome()

    data class CreationFailed(val message: String, override val recognizer: String) : RecognizeOutcome()

    /** [T-431-cancel] The caller went away; the session was cancelled. */
    data class Cancelled(override val recognizer: String) : RecognizeOutcome()
}

/**
 * [T-431-cancel] Reads a caller's `/proc/<pid>/status`. The caller is a proot
 * tracee: during the offload call it sits in a ptrace stop, so a SIGTERM or
 * SIGINT does not end it until proot gets back to it - which is after the host
 * replies. The process therefore stays in /proc; what changes at once is the
 * pending-signal mask (checked on a Pixel 4a: ShdPnd 0x4000 after SIGTERM).
 * Minis Ultra' own stop sends SIGTERM then SIGKILL to the group, a terminal Ctrl-C
 * SIGINT; a SIGKILLed process becomes a zombie.
 */
internal object CallerStatus {
    /** SIGHUP(1), SIGINT(2), SIGKILL(9), SIGTERM(15) as bits of a signal mask. */
    private const val STOP_SIGNALS: Long = (1L shl 0) or (1L shl 1) or (1L shl 8) or (1L shl 14)

    fun alive(status: String?): Boolean {
        if (status == null) return false
        val state = Regex("^State:\\s*(\\S)", RegexOption.MULTILINE).find(status)?.groupValues?.get(1)
        if (state == "Z" || state == "X") return false
        return (mask(status, "SigPnd") or mask(status, "ShdPnd")) and STOP_SIGNALS == 0L
    }

    private fun mask(status: String, field: String): Long =
        Regex("^$field:\\s*([0-9a-fA-F]+)", RegexOption.MULTILINE).find(status)?.groupValues?.get(1)
            ?.toULongOrNull(16)?.toLong() ?: 0L
}

internal object Recognizers {
    const val REGULAR = "regular"
    const val ON_DEVICE = "on_device"
}

internal class AndroidSpeechSystem(private val context: Context) : SpeechSystem {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val provider by lazy { SpeechProviderBridge(context) }

    override fun voiceInputModels() = runCatching { provider.models() }.getOrElse {
        AppLogger.warning(TAG, "voice input models: ${it.message}")
        emptyList()
    }

    override fun recordMic(seconds: Int, isCancelled: () -> Boolean) = provider.record(seconds, isCancelled)

    override fun callerAlive(pid: Int): Boolean =
        pid <= 0 || CallerStatus.alive(runCatching { File("/proc/$pid/status").readText() }.getOrNull())
    override fun decodeAudioFile(file: File, isCancelled: () -> Boolean) = provider.decode(file, isCancelled)
    override fun providerTranscribe(wav: ByteArray, language: String?) = provider.transcribe(wav, language)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun environment(): SpeechCli.Environment {
        val services = runCatching {
            context.packageManager
                .queryIntentServices(Intent(RECOGNITION_SERVICE_ACTION), 0)
                .mapNotNull { info -> info.serviceInfo?.let { "${it.packageName}/${it.name}" } }
        }.getOrElse {
            AppLogger.warning(TAG, "queryIntentServices failed: ${it.message}")
            emptyList()
        }
        // Readable by ordinary apps: SpeechRecognizer reads it in-process.
        val def = runCatching {
            Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        } else {
            null
        }
        return SpeechCli.Environment(
            services = services,
            defaultService = def,
            onDeviceAvailable = onDevice,
            locale = RecognizerLocales.recognizerTag(Locale.getDefault()),
            hasMicPermission = hasMicPermission(),
            sdkInt = Build.VERSION.SDK_INT,
            lastError = lastError(),
        )
    }

    /**
     * The documented way to ask a recognizer for its languages: resolve the
     * voice-search activity, read its `android.speech.DETAILS` receiver and
     * send it an EXPLICIT ordered broadcast. The pre-#431 implicit broadcast
     * reaches no manifest receiver on Android 8+.
     */
    override fun voiceDetails(timeoutMs: Long): SpeechCli.VoiceDetails {
        val intent = runCatching { RecognizerIntent.getVoiceDetailsIntent(context) }.getOrNull()
            ?: return SpeechCli.VoiceDetails(
                null,
                "no voice-search app exposes a language-details receiver (getVoiceDetailsIntent returned null)",
            )
        val latch = CountDownLatch(1)
        val tags = AtomicReference<List<String>?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                @Suppress("DEPRECATION")
                tags.set(getResultExtras(true)?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES))
                latch.countDown()
            }
        }
        try {
            context.sendOrderedBroadcast(intent, null, receiver, mainHandler, 0, null, null)
        } catch (e: Throwable) {
            return SpeechCli.VoiceDetails(null, "broadcast to ${intent.component?.flattenToShortString()} failed: ${e.message}")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return SpeechCli.VoiceDetails(null, "${intent.component?.flattenToShortString()} did not answer within ${timeoutMs}ms")
        }
        val got = tags.get()
        val target = intent.component?.flattenToShortString()
        return when {
            got == null -> SpeechCli.VoiceDetails(null, "$target answered without a language list")
            got.isEmpty() -> SpeechCli.VoiceDetails(emptyList(), "$target answered with an empty language list")
            else -> SpeechCli.VoiceDetails(got, "$target")
        }
    }

    /** API 33+: ask the regular and the on-device recognizer directly. */
    override fun recognitionSupport(timeoutMs: Long): SpeechCli.Support? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val online = linkedSetOf<String>()
        val installed = linkedSetOf<String>()
        val supported = linkedSetOf<String>()
        val notes = mutableListOf<String>()
        var regularAnswered = false
        var onDeviceAnswered = false
        for (onDevice in listOf(false, true)) {
            val label = if (onDevice) Recognizers.ON_DEVICE else Recognizers.REGULAR
            if (onDevice && runCatching { !SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(true)) {
                notes += "$label: not available"
                continue
            }
            val r = querySupport(onDevice, timeoutMs)
            when (r) {
                is SupportAnswer.Ok -> {
                    online += r.online
                    installed += r.installed
                    supported += r.supported
                    if (onDevice) onDeviceAnswered = true else regularAnswered = true
                    notes += "$label: ok"
                }
                is SupportAnswer.Error -> notes += "$label: ${RecognizerErrorCodes.name(r.code)}"
                SupportAnswer.Timeout -> notes += "$label: no answer within ${timeoutMs}ms"
                is SupportAnswer.Threw -> notes += "$label: ${r.message}"
            }
        }
        return SpeechCli.Support(
            online.toList(), installed.toList(), supported.toList(), notes.joinToString("; "),
            regularAnswered = regularAnswered,
            onDeviceAnswered = onDeviceAnswered,
        )
    }

    private sealed class SupportAnswer {
        class Ok(val online: List<String>, val installed: List<String>, val supported: List<String>) : SupportAnswer()
        class Error(val code: Int) : SupportAnswer()
        object Timeout : SupportAnswer()
        class Threw(val message: String) : SupportAnswer()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun querySupport(onDevice: Boolean, timeoutMs: Long): SupportAnswer {
        val latch = CountDownLatch(1)
        val answer = AtomicReference<SupportAnswer>(SupportAnswer.Timeout)
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        val executor = Executor { mainHandler.post(it) }
        mainHandler.post {
            try {
                val r = if (onDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizerRef.set(r)
                r.checkRecognitionSupport(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    executor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            answer.set(
                                SupportAnswer.Ok(
                                    recognitionSupport.onlineLanguages,
                                    recognitionSupport.installedOnDeviceLanguages,
                                    recognitionSupport.supportedOnDeviceLanguages,
                                ),
                            )
                            latch.countDown()
                        }

                        override fun onError(error: Int) {
                            answer.set(SupportAnswer.Error(error))
                            latch.countDown()
                        }
                    },
                )
            } catch (e: Throwable) {
                answer.set(SupportAnswer.Threw(e.message ?: e.javaClass.simpleName))
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        mainHandler.post { runCatching { recognizerRef.getAndSet(null)?.destroy() } }
        return answer.get()
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The 3-stage flow the chat composer's mic button uses (T67). */
    override fun requestMicPermission(): OffloadPermissionManager.AndroidPermissionResult = runBlocking {
        var r = OffloadPermissionManager.requestAndroidPermission(listOf(Manifest.permission.RECORD_AUDIO))
        if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
            OffloadPermissionManager.pollForPermissionGrant({ hasMicPermission() })
        ) {
            AppLogger.info(TAG, "Mic permission granted during post-DENY poll")
            r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
        }
        if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
            r = OffloadPermissionManager.requestSettingsGate(
                OffloadPermissionManager.SettingsGateRequest(
                    id = Manifest.permission.RECORD_AUDIO,
                    title = "Microphone permission needed",
                    message = "Minis Ultra needs microphone permission to transcribe speech. Open Settings to allow it.",
                    settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    requiresPackageUri = true,
                    positiveLabel = "Open Settings",
                ),
                check = { hasMicPermission() },
            )
        }
        r
    }

    /**
     * One recognition session on the recognizer the request names.
     * [T-431-fallback] Pre-#431 the CLI always took the on-device recognizer on
     * S+ and never fell back; the handler now plans the order.
     */
    override fun recognize(request: RecognizeRequest): RecognizeOutcome =
        session(request, request.onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    override fun micBusyReason(): String? {
        val state = com.openminis.app.speech.SpeechRecognitionManager.state.value
        return when (state) {
            com.openminis.app.speech.RecognitionState.STARTING,
            com.openminis.app.speech.RecognitionState.RECORDING,
            -> "The chat voice input is using the microphone (state: $state)."
            else -> null
        }
    }

    /**
     * [T-android-speech-error10] Every exit path destroys the recognizer
     * exactly once, on the main looper that created it - an orphaned session
     * exhausts the system recognizer service (ERROR_TOO_MANY_REQUESTS, 10).
     */
    private fun session(request: RecognizeRequest, onDevice: Boolean): RecognizeOutcome {
        val label = if (onDevice) Recognizers.ON_DEVICE else Recognizers.REGULAR
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<RecognizeOutcome?>(null)
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        val destroyed = AtomicBoolean(false)
        fun destroyOnce() {
            if (!destroyed.compareAndSet(false, true)) return
            val r = recognizerRef.getAndSet(null) ?: return
            try {
                r.destroy()
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "recognizer destroy threw: ${e.message}")
            }
        }
        fun finish(o: RecognizeOutcome) {
            if (outcome.compareAndSet(null, o)) latch.countDown()
            destroyOnce()
        }

        mainHandler.post {
            val recognizer = try {
                if (onDevice) {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } catch (_: Throwable) {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Throwable) {
                finish(RecognizeOutcome.CreationFailed("Could not create SpeechRecognizer: ${e.message}", label))
                return@post
            }
            recognizerRef.set(recognizer)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, request.maxResults)
                request.language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val confs = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList()
                    finish(RecognizeOutcome.Success(matches, confs, label))
                }

                override fun onError(error: Int) = finish(RecognizeOutcome.Failure(error, label))
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            try {
                recognizer.startListening(intent)
            } catch (e: Throwable) {
                finish(RecognizeOutcome.CreationFailed("startListening threw: ${e.message}", label))
            }
        }

        // [T-431-cancel] A caller that went away cancels the session now,
        // freeing the microphone, instead of at the end of --duration.
        fun cancelled(): RecognizeOutcome {
            mainHandler.post {
                runCatching { recognizerRef.get()?.cancel() }
                finish(RecognizeOutcome.Cancelled(label))
            }
            return RecognizeOutcome.Cancelled(label)
        }

        // [T-431] --duration is the recording length, as on iOS: stop
        // listening when it is up and give the recognizer a grace period to
        // deliver the final result. Pre-#431 the duration was only a timeout,
        // so a quiet room always ended as "timed out".
        when (awaitOrCancel(latch, request.durationSec * 1000, request.isCancelled)) {
            Wait.DONE -> Unit
            Wait.CANCELLED -> return cancelled()
            Wait.TIME_UP -> {
                mainHandler.post {
                    if (!destroyed.get()) runCatching { recognizerRef.get()?.stopListening() }
                }
                when (awaitOrCancel(latch, RESULT_GRACE_SEC * 1000, request.isCancelled)) {
                    Wait.DONE -> Unit
                    Wait.CANCELLED -> return cancelled()
                    Wait.TIME_UP -> {
                        // [T-android-speech-error10] Never leave the session behind.
                        mainHandler.post {
                            runCatching { recognizerRef.get()?.cancel() }
                            finish(RecognizeOutcome.Timeout(request.durationSec + RESULT_GRACE_SEC, label))
                        }
                        return RecognizeOutcome.Timeout(request.durationSec + RESULT_GRACE_SEC, label)
                    }
                }
            }
        }
        return outcome.get() ?: RecognizeOutcome.Timeout(request.durationSec, label)
    }

    private enum class Wait { DONE, TIME_UP, CANCELLED }

    /** Wait up to [millis] for [latch], checking [isCancelled] every 250 ms. */
    private fun awaitOrCancel(latch: CountDownLatch, millis: Long, isCancelled: () -> Boolean): Wait {
        val end = System.currentTimeMillis() + millis
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return Wait.TIME_UP
            if (latch.await(minOf(left, 250L), TimeUnit.MILLISECONDS)) return Wait.DONE
            if (isCancelled()) return Wait.CANCELLED
        }
    }

    override fun probe(timeoutMs: Long): JSONObject {
        val started = System.currentTimeMillis()
        val ready = CountDownLatch(1)
        val result = AtomicReference(JSONObject().put("ok", false).put("result", "no_callback"))
        val recognizerRef = AtomicReference<SpeechRecognizer?>(null)
        mainHandler.post {
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(context)
                recognizerRef.set(r)
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        result.set(JSONObject().put("ok", true).put("result", "ready_for_speech"))
                        ready.countDown()
                    }

                    override fun onError(error: Int) {
                        result.set(
                            JSONObject().put("ok", false).put("result", "error")
                                .put("error_code", error).put("error_name", RecognizerErrorCodes.name(error)),
                        )
                        ready.countDown()
                    }

                    override fun onResults(results: Bundle?) = ready.countDown()
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
            } catch (e: Throwable) {
                result.set(JSONObject().put("ok", false).put("result", "threw").put("message", e.message ?: ""))
                ready.countDown()
            }
        }
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            result.set(JSONObject().put("ok", false).put("result", "no_callback_within_${timeoutMs}ms"))
        }
        mainHandler.post {
            recognizerRef.getAndSet(null)?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
        }
        return result.get().put("recognizer", Recognizers.REGULAR).put("ms", System.currentTimeMillis() - started)
    }

    /**
     * Resolve a `/var/minis/...`-style Linux path to a host File via
     * [PRootKernel]'s global bind-mount table.
     */
    override fun resolveSourcePath(source: String): File? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("/")) PRootKernel.resolveHostPath(trimmed) ?: File(trimmed) else File(trimmed)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "resolveSourcePath('$trimmed') failed: ${e.message}")
            null
        }
    }

    override fun recordLastError(code: Int?, recognizer: String?) {
        val edit = prefs.edit()
        if (code == null) {
            edit.remove(KEY_LAST_CODE).remove(KEY_LAST_AT).remove(KEY_LAST_RECOGNIZER)
        } else {
            edit.putInt(KEY_LAST_CODE, code).putLong(KEY_LAST_AT, System.currentTimeMillis())
                .putString(KEY_LAST_RECOGNIZER, recognizer)
        }
        edit.apply()
    }

    private fun lastError(): SpeechCli.LastError? {
        if (!prefs.contains(KEY_LAST_CODE)) return null
        return SpeechCli.LastError(
            prefs.getInt(KEY_LAST_CODE, 0),
            prefs.getLong(KEY_LAST_AT, 0L),
            prefs.getString(KEY_LAST_RECOGNIZER, null),
        )
    }

    companion object {
        private const val TAG = "SpeechOffload"
        private const val PREFS = "android_speech_cli"
        private const val KEY_LAST_CODE = "last_error_code"
        private const val KEY_LAST_AT = "last_error_at"
        private const val KEY_LAST_RECOGNIZER = "last_error_recognizer"
        private const val RECOGNITION_SERVICE_ACTION = "android.speech.RecognitionService"

        /** After stopListening, how long the recognizer may take to answer. */
        private const val RESULT_GRACE_SEC = 8L

        /** `Settings.Secure.VOICE_RECOGNITION_SERVICE` is @hide; the key is not. */
        private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
    }
}
