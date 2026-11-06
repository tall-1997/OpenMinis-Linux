package com.openminis.app.provider.voice

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * [FIX-261022-06, OpenMinis#468 / #469] Wire formats of the cloud ASR vendors
 * whose protocol is not OpenAI's /audio/transcriptions, kept free of Android
 * and network types so they are unit-tested as plain JVM code. iOS
 * (CloudASRProtocols.swift) and Windows (speech/cloud_asr.rs) build the same
 * requests; the three are checked against the same vectors.
 *
 * Sources (fetched 2026-10-22):
 * - Bailian qwen3-asr-flash, OpenAI-compatible mode:
 *   help.aliyun.com/zh/model-studio/qwen-asr-api-reference
 * - iFlytek dictation (streaming) v2: www.xfyun.cn/doc/asr/voicedictation/API.html
 * - Volcengine flash recognition: www.volcengine.com/docs/6561/1631584
 */
object CloudAsrProtocols {

    // -- Audio: any PCM WAV -> 16 kHz mono 16-bit PCM -------------------------

    const val PCM_RATE = 16_000

    /**
     * The samples of a RIFF/WAVE file (16-bit integer or 32-bit float PCM, any
     * rate, any channel count) as 16 kHz mono 16-bit little-endian PCM, or
     * null when [wav] is not such a file. Channels are averaged; the rate is
     * converted by linear interpolation, which is enough for speech.
     */
    fun pcm16kMono(wav: ByteArray): ByteArray? {
        if (wav.size < 12 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val len = buf.getInt(pos + 4)
            val body = pos + 8
            if (len < 0) return null
            if (id == "fmt " && body + 16 <= wav.size) {
                format = buf.getShort(body).toInt() and 0xFFFF
                channels = buf.getShort(body + 2).toInt() and 0xFFFF
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt() and 0xFFFF
                if (format == 0xFFFE && len >= 26 && body + 26 <= wav.size) {
                    // WAVE_FORMAT_EXTENSIBLE: the real format is the sub-format's first word.
                    format = buf.getShort(body + 24).toInt() and 0xFFFF
                }
            } else if (id == "data") {
                dataStart = body
                // A streamed WAV may carry 0 or 0xFFFFFFFF here: take what is there.
                dataLen = minOf(len.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE, (wav.size - body).toLong()).toInt()
                break
            }
            pos = body + len + (len and 1)
        }
        val isInt16 = format == 1 && bits == 16
        val isFloat32 = format == 3 && bits == 32
        if (dataStart < 0 || channels <= 0 || rate <= 0 || !(isInt16 || isFloat32)) return null

        val frameBytes = channels * bits / 8
        val frames = dataLen / frameBytes
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val at = dataStart + f * frameBytes + c * bits / 8
                sum += if (isInt16) buf.getShort(at) / 32768f else buf.getFloat(at)
            }
            mono[f] = sum / channels
        }
        val outFrames = if (rate == PCM_RATE) frames else (frames.toLong() * PCM_RATE / rate).toInt()
        val out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        val step = rate.toDouble() / PCM_RATE
        for (i in 0 until outFrames) {
            val x = i * step
            val i0 = x.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val v = mono[i0] + (mono[i1] - mono[i0]) * (x - i0).toFloat()
            out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return out.array()
    }

    // -- iFlytek dictation (streaming) v2: wss://iat-api.xfyun.cn/v2/iat --------

    /** One session takes at most 60 s of audio (error 10114): cut at 55 s. */
    const val XUNFEI_SEGMENT_BYTES = 55 * PCM_RATE * 2

    /** Bytes per message: two 40 ms frames of 16 kHz PCM, sent every 40 ms. */
    const val XUNFEI_CHUNK_BYTES = 2 * 1280
    const val XUNFEI_CHUNK_INTERVAL_MS = 40L

    /** "zh-CN" / "en" / null -> the `business.language` value; Chinese unless English. */
    fun xunfeiLanguage(tag: String?): String =
        if (VoiceProvider.iso6391(tag) == "en") "en_us" else "zh_cn"

    /** [pcm] (16 kHz mono 16-bit) in sessions of at most [XUNFEI_SEGMENT_BYTES]. */
    fun xunfeiSegments(pcm: ByteArray): List<ByteArray> =
        if (pcm.isEmpty()) emptyList()
        else (pcm.indices step XUNFEI_SEGMENT_BYTES).map { pcm.copyOfRange(it, minOf(it + XUNFEI_SEGMENT_BYTES, pcm.size)) }

    /**
     * The text messages of one session: the first carries `common` and
     * `business`, every audio message carries `data` with status 0 (first) or
     * 1, and the session ends with `{"data":{"status":2}}`, which must be sent.
     */
    fun xunfeiFrames(pcm: ByteArray, appId: String, language: String): List<String> {
        val enc = Base64.getEncoder()
        val chunks = if (pcm.isEmpty()) listOf(ByteArray(0))
        else (pcm.indices step XUNFEI_CHUNK_BYTES).map { pcm.copyOfRange(it, minOf(it + XUNFEI_CHUNK_BYTES, pcm.size)) }
        val out = ArrayList<String>(chunks.size + 1)
        chunks.forEachIndexed { i, chunk ->
            val data = JSONObject()
                .put("status", if (i == 0) 0 else 1)
                .put("format", "audio/L16;rate=16000")
                .put("encoding", "raw")
                .put("audio", enc.encodeToString(chunk))
            val msg = JSONObject()
            if (i == 0) {
                msg.put("common", JSONObject().put("app_id", appId))
                msg.put(
                    "business",
                    JSONObject()
                        .put("language", language)
                        .put("domain", "iat")
                        .put("accent", "mandarin")
                        // A recorded clip may hold pauses: do not end at the default 2 s of silence.
                        .put("eos", 10_000),
                )
            }
            out += msg.put("data", data).toString()
        }
        out += JSONObject().put("data", JSONObject().put("status", 2)).toString()
        return out
    }

    /**
     * Collects the result messages of one session. Without `dwa=wpgs` every
     * piece is final and they are joined in `sn` order; a `pgs:"rpl"` piece
     * (dynamic correction) replaces the pieces named by `rg` as well.
     */
    class XunfeiResult {
        private val pieces = sortedMapOf<Int, String>()

        /** Thrown for `code != 0`. */
        class Failure(val code: Int, message: String) : Exception("iFlytek error $code: $message")

        /** Feeds one server message; true once the last result (data.status 2) arrived. */
        fun accept(message: String): Boolean {
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return false
            val code = json.optInt("code", 0)
            if (code != 0) throw Failure(code, json.optString("message"))
            val data = json.optJSONObject("data") ?: return false
            data.optJSONObject("result")?.let { result ->
                val sn = result.optInt("sn", pieces.size + 1)
                if (result.optString("pgs") == "rpl") {
                    val rg = result.optJSONArray("rg")
                    if (rg != null && rg.length() == 2) {
                        for (k in rg.optInt(0)..rg.optInt(1)) pieces.remove(k)
                    }
                }
                pieces[sn] = words(result.optJSONArray("ws"))
            }
            return data.optInt("status", 0) == 2
        }

        val text: String get() = pieces.values.joinToString("")

        private fun words(ws: JSONArray?): String {
            ws ?: return ""
            val sb = StringBuilder()
            for (i in 0 until ws.length()) {
                // cw[0] is the best candidate; the others only appear with wbest.
                ws.optJSONObject(i)?.optJSONArray("cw")?.optJSONObject(0)?.optString("w")?.let(sb::append)
            }
            return sb.toString()
        }
    }

    // -- Bailian qwen3-asr-flash: {base}/compatible-mode/v1/chat/completions ---

    const val BAILIAN_ASR_MODEL = "qwen3-asr-flash"

    /**
     * The model a Bailian transcription runs on. Paraformer / SenseVoice / Fun-ASR
     * ids (the old template default was paraformer-realtime-v2) are WebSocket or
     * file-URL only and have no route in the compatible mode, so they go to
     * qwen3-asr-flash, the one ASR family that mode serves.
     */
    fun bailianModel(requested: String?): String {
        val id = requested?.trim().orEmpty()
        return if (id.lowercase().startsWith("qwen") && id.lowercase().contains("asr")) id else BAILIAN_ASR_MODEL
    }

    /**
     * The request body: the clip as a data URL in an `input_audio` part, and
     * `asr_options.language` only when the language is known (the docs: leave
     * it out for uncertain or mixed speech).
     */
    fun bailianBody(model: String, wav: ByteArray, language: String?): JSONObject {
        val audio = JSONObject().put(
            "data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav),
        )
        val user = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "input_audio").put("input_audio", audio)))
        val options = JSONObject().put("enable_itn", false)
        VoiceProvider.iso6391(language)?.let { options.put("language", it) }
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(user))
            .put("stream", false)
            .put("asr_options", options)
    }

    /** `choices[0].message.content` and `usage.seconds`, or null for another shape. */
    fun bailianParse(body: String): Pair<String, Double?>? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        if (!message.has("content")) return null
        val seconds = json.optJSONObject("usage")?.optDouble("seconds")?.takeIf { !it.isNaN() }
        return message.optString("content") to seconds
    }

    // -- Volcengine flash recognition ------------------------------------------

    /** The flash API's resource; the docs' model list (2026-09-29) files it under model 2.0. */
    const val DOUBAO_FLASH_RESOURCE = "volc.bigasr.auc_turbo"

    /**
     * The `X-Api-Resource-Id` to send: a model id that is itself a resource id
     * ("volc.…") is used as is, so a resource Volcengine grants an account can
     * be entered as the model without an app update; anything else is the
     * flash resource.
     */
    fun doubaoResource(model: String?): String =
        model?.trim()?.takeIf { it.startsWith("volc.") } ?: DOUBAO_FLASH_RESOURCE

    fun doubaoBody(wav: ByteArray): JSONObject = JSONObject()
        .put("user", JSONObject().put("uid", "minis_user"))
        .put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(wav)))
        // enable_punc defaults to false in the API, which left dictation unpunctuated.
        .put("request", JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true))

    /**
     * A readable message for a failed flash call. The service reports in the
     * X-Api-Status-Code / X-Api-Message headers and the body may be empty.
     */
    fun doubaoFailure(statusCode: String?, message: String?, httpCode: Int, resource: String): String {
        val code = statusCode?.takeIf { it.isNotBlank() }
        val detail = message?.takeIf { it.isNotBlank() }
        val head = "Doubao ASR failed" + (code?.let { " ($it)" } ?: " (HTTP $httpCode)") + (detail?.let { ": $it" } ?: "")
        return when {
            code == "45000010" || httpCode == 401 -> "$head. Check the API Key (Volcengine new console)."
            httpCode == 403 || detail?.contains("resource", ignoreCase = true) == true ||
                detail?.contains("grant", ignoreCase = true) == true ->
                "$head. The account may not have the \"$resource\" resource enabled: enable recording file " +
                    "recognition (flash) in the Volcengine console, or enter the resource id you were granted as the model."
            code == "20000003" -> "$head. No speech was detected."
            else -> head
        }
    }
}
