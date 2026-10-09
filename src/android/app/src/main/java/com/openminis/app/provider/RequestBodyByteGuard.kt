package com.openminis.app.provider

import com.openminis.app.harness.context.ContextWindowPolicy
import org.json.JSONObject

/**
 * [T-context-window-policy] 请求体物理体积守卫（COMPARISON-TAIXU P1-4 的字节半边）。
 *
 * token 预算看不见 UTF-8/JSON 转义膨胀与 Base64 图片；中转 Nginx 的
 * `client_max_body_size` 往往只有 1–10MB，超限即 HTTP 413——而 413 在 provider
 * 层长得像「上游抽风」，重试一万次也是同一个死法。这里在序列化出口做硬限：
 *
 *  1. 不超限原样返回（热路径一次 toString + 一次字节计数，无其它开销）；
 *  2. 超限先剥图片块（体积在 Base64 而非文本），留 [ContextWindowPolicy.stripImagesNote]
 *     标记——图片本体仍在落库 transcript 与 UI 中可见；
 *  3. 剥完仍超限**响亮失败**：抛带明确文案的异常，而不是把请求送出去等 413。
 *     工具输出不需要在这里压：ToolOutputSpill 已把单条输出限在 16k，taixu 的
 *     「第一把刀」对我方冗余。
 *
 * 只接 Anthropic 路径：OpenAI 路径的请求体是流式写出，测量即等于全量序列化一次，
 * 代价翻倍；该路径的体积治理靠 effectiveAgentHistory 的巨型消息截断兜底。
 */
object RequestBodyByteGuard {

    fun enforce(body: JSONObject): String {
        var serialized = body.toString()
        if (serialized.toByteArray(Charsets.UTF_8).size <= ContextWindowPolicy.REQUEST_BODY_HARD_LIMIT_BYTES) {
            return serialized
        }
        val stripped = stripImages(body)
        serialized = body.toString()
        val bytes = serialized.toByteArray(Charsets.UTF_8).size
        if (bytes > ContextWindowPolicy.REQUEST_BODY_HARD_LIMIT_BYTES) {
            throw IllegalStateException(
                "request body is $bytes bytes, over the ${ContextWindowPolicy.REQUEST_BODY_HARD_LIMIT_BYTES}-byte " +
                    "hard limit even after stripping $stripped image block(s); spill or truncate the payload before retrying",
            )
        }
        return serialized
    }

    /** 剥掉 anthropic 形状里的 image 块并留标记；返回剥掉的块数。 */
    fun stripImages(body: JSONObject): Int {
        val messages = body.optJSONArray("messages") ?: return 0
        var stripped = 0
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val content = message.optJSONArray("content") ?: continue
            val kept = org.json.JSONArray()
            var imagesHere = 0
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j)
                if (block != null && block.optString("type") == "image") {
                    imagesHere++
                } else {
                    kept.put(block ?: content.opt(j))
                }
            }
            if (imagesHere == 0) continue
            stripped += imagesHere
            kept.put(JSONObject().put("type", "text").put("text", ContextWindowPolicy.stripImagesNote(imagesHere)))
            message.put("content", kept)
        }
        return stripped
    }
}
