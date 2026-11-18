package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-nonstream-json-default] Performance roadmap §4-P0: full-body JSON parsing
 * is a CPU-bound operation (a 3 MB response costs 500-800 ms of tokenizer work)
 * that all three providers used to run on `Dispatchers.IO` — the pool sized for
 * blocking I/O. While one request parses, concurrent DB reads and network calls
 * queue behind it. The raw-body READ (network I/O) stays on the caller's
 * thread; only the PARSE moves to `Dispatchers.Default` (== CPU core count,
 * sized for compute).
 *
 * Defense is centralized here too: a raw `org.json.JSONException` escaping the
 * provider names tokenizer internals ("Value data of type java.lang.String
 * cannot be converted..."), not the actual mismatch. Anthropic/Gemini
 * previously let it escape; OpenAI's chat-completions JSON branch already
 * wrapped it. All three now throw [LLMError.DecodingError] with the endpoint
 * label and a bounded body prefix — the same shape the sniff branch
 * ([T-stream-content-type-sniff]) already used, so the agent-loop handling
 * chain (persisted inline errors, retry affordance) treats all three
 * identically.
 *
 * [T-fullbodyjson-bom-and-empty] OpenAI-compatible relays (user reports:
 * token.zlshag.xyz) occasionally answer a JSON request with a body that starts
 * with a UTF-8 BOM (`\uFEFF`) or is empty / whitespace-only. `JSONObject(raw)`
 * rejects both: a BOM is not JSON whitespace, so it throws "Value ? of type
 * java.lang.String cannot be converted to JSONObject" whose `message` the
 * wrapper renders as "…but got: ?" — with `raw.take(120)` after a zero-width
 * BOM the excerpt renders EMPTY, which is exactly the "but got: " tail users
 * report. Both causes are structural, not per-relay, so the fix lives here:
 *
 *  1. BOM is stripped before parsing (UTF-8/UTF-16LE/UTF-16BE signatures).
 *  2. Whitespace-only / empty bodies get a message that states the actual
 *     fact ("empty body (N bytes) …") instead of an org.json internal string.
 *  3. Non-object, non-empty bodies keep the bounded raw excerpt, with the
 *     excerpt stripped first so control characters and BOMs can no longer
 *     blank out the "but got:" tail.
 */
internal object FullBodyJson {

    /** UTF-8 / UTF-16LE / UTF-16BE byte-order marks seen on relay bodies. */
    private val BOMS = listOf("\uFEFF", "\uFFFE", "\uFEFF")

    /**
     * Strips leading BOMs and whitespace from a decoded body. BOMs are not
     * JSON whitespace per RFC 8259, yet `InputStreamReader` over UTF-8 keeps
     * U+FEFF as a real char — relays that prepend it made every
     * non-streaming answer throw. Loop strips repeats (`\uFEFF\uFEFF{`).
     */
    internal fun stripBomAndTrim(raw: String): String {
        var s = raw
        while (true) {
            val trimmed = s.trimStart('\uFEFF', '\uFFFE', ' ', '\t', '\r', '\n')
            if (trimmed.length == s.length) return s.trim()
            s = trimmed
        }
    }

    /**
     * Parses [raw] on `Dispatchers.Default`. [debugLabel] names the endpoint /
     * content-type context so a decoding error stays actionable ("expected a
     * JSON body (chat-completions body, Content-Type: application/json) but
     * got: …" instead of a bare tokenizer stack).
     */
    internal suspend fun parse(raw: String, debugLabel: String): JSONObject =
        withContext(Dispatchers.Default) {
            val prepared = stripBomAndTrim(raw)
            try {
                JSONObject(prepared)
            } catch (e: Exception) {
                // [T-fullbodyjson-bom-and-empty] An org.json exception on an
                // empty string is "end of input at character 0" — meaningless
                // to a user. Say what actually arrived instead.
                val excerpt =
                    if (prepared.isEmpty()) {
                        "empty body (${raw.length} bytes after trim) — upstream returned no JSON"
                    } else {
                        prepared.take(120).replace('\n', ' ')
                    }
                throw LLMError.DecodingError(
                    IllegalStateException(
                        "expected a JSON object body ($debugLabel) but got: $excerpt",
                        e,
                    ),
                )
            }
        }
}
