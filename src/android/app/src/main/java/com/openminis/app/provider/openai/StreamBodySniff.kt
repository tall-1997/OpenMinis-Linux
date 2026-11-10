package com.openminis.app.provider.openai

/**
 * [T-stream-content-type-sniff] How many leading bytes are peeked (and pushed
 * back) to decide whether a body is SSE or a single JSON object.
 */
internal const val SNIFF_BYTES = 256

/**
 * Read enough of [stream] to classify it, then push every byte back so the
 * caller can still consume the body normally — including incrementally.
 *
 * Two traps this avoids:
 * - **Byte-at-a-time reads.** Looping over `read()` for a single byte would
 *   issue one socket read per byte, so this does bulk reads instead.
 * - **Partial reads.** TCP may hand over `dat` of `data: {…}`; classifying on
 *   that would wrongly conclude "not SSE". So keep reading until the trimmed
 *   prefix is long enough to contain a full SSE field name, or a line break,
 *   or the stream ends.
 */
internal fun peekPrefix(stream: java.io.PushbackInputStream): String {
    val buf = ByteArray(SNIFF_BYTES)
    var n = 0
    while (n < buf.size) {
        val read = stream.read(buf, n, buf.size - n)
        if (read == -1) break
        n += read
        val trimmed = String(buf, 0, n, Charsets.UTF_8).trimStart()
        if (trimmed.length >= 6 || '\n' in trimmed) break
    }
    if (n > 0) stream.unread(buf, 0, n)
    return String(buf, 0, n, Charsets.UTF_8)
}

/**
 * Pure: does this body prefix carry SSE framing?
 *
 * The Content-Type header is not a substitute. Relays behind a proxy that hides
 * or rewrites the header answer `application/json` while streaming perfectly
 * valid `data: {…}` events, and feeding that body to `JSONObject()` produces
 * "Value data of type java.lang.String cannot be converted to JSONObject" —
 * the tokenizer's first value is the bare word `data`. The converse is just as
 * real: a gateway that ignores `stream=true` returns one JSON object with an
 * `text/event-stream`-less content type, which is why the JSON branch exists at
 * all. Both directions are decided by the bytes, not the header.
 */
internal fun looksLikeSse(prefix: String): Boolean {
    val head = prefix.trimStart()
    return head.startsWith("data:") ||
        head.startsWith("event:") ||
        head.startsWith("id:") ||
        head.startsWith("retry:") ||
        // An SSE comment / keep-alive line, e.g. ": ping".
        head.startsWith(":")
}

