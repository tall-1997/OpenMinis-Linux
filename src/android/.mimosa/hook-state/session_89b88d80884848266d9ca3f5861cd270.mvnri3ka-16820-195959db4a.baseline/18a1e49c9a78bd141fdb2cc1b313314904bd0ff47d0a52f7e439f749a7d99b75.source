package com.openminis.app.provider

import org.json.JSONObject

/**
 * Host-based media-API routing for OpenAI-compatible custom bases.
 *
 * Relays (OpenRouter, vanity domains) stay on the OpenAI Images/Videos paths —
 * never switch to a first-party native protocol just because the model id
 * looks like Seedance / CogView / Wanx. Tests may override detection on
 * [com.openminis.app.provider.openai.OpenAIProvider].
 */
enum class VendorMediaKind {
    OPENAI_COMPAT,
    ARK,
    ZHIPU,
    DASHSCOPE,
    MINIMAX,
    DEEPSEEK,
    HUNYUAN_TC3,
    HUNYUAN_OPENAI,
    AGNES,
    KLING,
}

object VendorMedia {

    fun detect(basePath: String, modelId: String = ""): VendorMediaKind {
        val host = hostOf(basePath)
        val hay = "$host $basePath".lowercase()
        // Host wins. Model id is unused for protocol selection so a relay
        // hosting `doubao-seedance-*` is not rewritten onto Ark paths.
        @Suppress("UNUSED_VARIABLE")
        val ignoredModel = modelId
        if (host.contains("deepseek.com")) return VendorMediaKind.DEEPSEEK
        if (host.contains("klingai.com") || host.contains("klingai.cn")) return VendorMediaKind.KLING
        if (host.contains("hunyuan.tencentcloudapi.com") ||
            (host.contains("tencentcloudapi.com") && hay.contains("hunyuan"))
        ) {
            return VendorMediaKind.HUNYUAN_TC3
        }
        if (host.contains("hunyuan.cloud.tencent.com")) return VendorMediaKind.HUNYUAN_OPENAI
        if (host.contains("volces.com") || host.contains("volcengine.com") ||
            host.contains("volcengine.cn") || host.startsWith("ark.") ||
            host.contains(".ark.")
        ) {
            return VendorMediaKind.ARK
        }
        if (host.contains("bigmodel.cn") || host.contains("zhipuai.cn") ||
            host.contains("zhipuai.com")
        ) {
            return VendorMediaKind.ZHIPU
        }
        if (host.contains("dashscope")) return VendorMediaKind.DASHSCOPE
        if (host.contains("minimax.chat") || host.contains("minimax.io") ||
            host.contains("minimaxi.com")
        ) {
            return VendorMediaKind.MINIMAX
        }
        if (host.contains("agnes-ai.com") || host.contains("agnes-ai.cn")) {
            return VendorMediaKind.AGNES
        }
        return VendorMediaKind.OPENAI_COMPAT
    }

    fun unsupportedMessage(kind: VendorMediaKind, media: String): String? = when (kind) {
        VendorMediaKind.DEEPSEEK ->
            "DeepSeek does not offer an official $media generation API"
        VendorMediaKind.HUNYUAN_TC3 ->
            "Hunyuan's native Tencent Cloud API requires TC3 SecretId/SecretKey signing, " +
                "which this client does not support. Use the OpenAI-compatible Hunyuan " +
                "endpoint (api.hunyuan.cloud.tencent.com) if your account exposes $media generation."
        VendorMediaKind.KLING ->
            "Kling's native API requires access-key JWT signing, which this client does not support"
        else -> null
    }

    fun looksLikeSeedance(modelId: String): Boolean =
        modelId.lowercase().contains("seedance")

    fun looksLikeWanxNativeImage(modelId: String): Boolean {
        val id = modelId.lowercase()
        if (id.contains("t2v") || id.contains("video")) return false
        return id.contains("wanx") || id.contains("wan2.1-t2i") || id.contains("wan2.2-t2i") ||
            id.contains("wan2-t2i")
    }

    fun taskId(json: JSONObject): String {
        val direct = json.safeOptString("id", json.safeOptString("task_id", json.safeOptString("taskId", "")))
        if (direct.isNotEmpty()) return direct
        json.optJSONObject("output")?.let { out ->
            val nested = out.safeOptString("task_id", out.safeOptString("id", out.safeOptString("taskId", "")))
            if (nested.isNotEmpty()) return nested
        }
        json.optJSONObject("data")?.let { data ->
            val nested = data.safeOptString("task_id", data.safeOptString("id", ""))
            if (nested.isNotEmpty()) return nested
        }
        return ""
    }

    fun taskStatus(json: JSONObject): String {
        val direct = json.safeOptString("status", json.safeOptString("task_status", ""))
        if (direct.isNotEmpty()) return direct
        json.optJSONObject("output")?.let { out ->
            val nested = out.safeOptString("task_status", out.safeOptString("status", ""))
            if (nested.isNotEmpty()) return nested
        }
        json.optJSONObject("data")?.let { data ->
            val nested = data.safeOptString("status", data.safeOptString("task_status", ""))
            if (nested.isNotEmpty()) return nested
        }
        return ""
    }

    fun isFailedStatus(status: String): Boolean {
        val s = status.lowercase()
        return s == "failed" || s == "fail" || s == "error" || s == "cancelled" ||
            s == "canceled" || s == "failure"
    }

    fun isSuccessStatus(status: String): Boolean {
        val s = status.lowercase()
        return s == "succeeded" || s == "success" || s == "completed" || s == "succeed"
    }

    fun errorMessage(json: JSONObject): String {
        json.optJSONObject("error")?.let { err ->
            val msg = err.safeOptString("message", err.safeOptString("msg", err.safeOptString("code", "")))
            if (msg.isNotEmpty()) return msg
        }
        val msg = json.safeOptString("message", json.safeOptString("msg", ""))
        if (msg.isNotEmpty()) return msg
        json.optJSONObject("output")?.let { out ->
            val nested = out.safeOptString("message", "")
            if (nested.isNotEmpty()) return nested
            out.optJSONObject("error")?.safeOptString("message", "")?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    fun extractHttpVideoUrl(json: JSONObject): String? {
        httpUrlIn(json)?.let { return it }
        json.optJSONObject("content")?.let { httpUrlIn(it)?.let { url -> return url } }
        json.optJSONObject("output")?.let { out ->
            httpUrlIn(out)?.let { return it }
            out.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    httpUrlIn(arr.optJSONObject(i))?.let { return it }
                }
            }
            out.optJSONObject("video")?.let { httpUrlIn(it)?.let { url -> return url } }
        }
        json.optJSONObject("result")?.let { httpUrlIn(it)?.let { url -> return url } }
        json.optJSONObject("data")?.let { data ->
            httpUrlIn(data)?.let { return it }
            data.optJSONArray("video_result")?.let { arr ->
                for (i in 0 until arr.length()) {
                    httpUrlIn(arr.optJSONObject(i))?.let { return it }
                }
            }
        }
        json.optJSONArray("video_result")?.let { arr ->
            for (i in 0 until arr.length()) {
                httpUrlIn(arr.optJSONObject(i))?.let { return it }
            }
        }
        json.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                httpUrlIn(arr.optJSONObject(i))?.let { return it }
            }
        }
        return null
    }

    fun extractHttpImageUrls(json: JSONObject): List<String> {
        val out = mutableListOf<String>()
        fun add(url: String?) {
            if (!url.isNullOrBlank() && url.startsWith("http")) out.add(url)
        }
        json.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                add(httpUrlIn(arr.optJSONObject(i)))
            }
        }
        json.optJSONObject("data")?.let { data ->
            add(httpUrlIn(data))
            data.optJSONArray("image_urls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = dataArrayString(arr, i)
                    add(v)
                }
            }
        }
        json.optJSONObject("output")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                add(httpUrlIn(arr.optJSONObject(i)))
            }
        }
        add(httpUrlIn(json))
        return out.distinct()
    }

    fun minimaxImageBase64(json: JSONObject): List<String> {
        val data = json.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("image_base64")
        if (arr != null) {
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = dataArrayString(arr, i)
                if (v.isNotEmpty()) out.add(v)
            }
            return out
        }
        val single = data.safeOptString("image_base64", "")
        return if (single.isNotEmpty()) listOf(single) else emptyList()
    }

    fun minimaxFileId(json: JSONObject): String {
        val direct = json.safeOptString("file_id", json.safeOptString("fileId", ""))
        if (direct.isNotEmpty()) return direct
        json.optJSONObject("data")?.let { data ->
            val nested = data.safeOptString("file_id", data.safeOptString("fileId", ""))
            if (nested.isNotEmpty()) return nested
        }
        return ""
    }

    private fun hostOf(basePath: String): String {
        val raw = basePath.trim()
        if (raw.isEmpty()) return ""
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        return try {
            java.net.URI(withScheme).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            raw.lowercase()
        }
    }

    private fun httpUrlIn(obj: JSONObject?): String? {
        if (obj == null) return null
        for (key in listOf(
            "video_url", "image_url", "url", "download_url", "file_url",
            "output_url", "mp4_url",
        )) {
            val v = obj.safeOptString(key, "")
            if (v.startsWith("http")) return v
        }
        val output = obj.safeOptString("output", "")
        if (output.startsWith("http")) return output
        return null
    }

    private fun dataArrayString(arr: org.json.JSONArray, index: Int): String {
        if (arr.isNull(index)) return ""
        return arr.optString(index, "")
    }
}
