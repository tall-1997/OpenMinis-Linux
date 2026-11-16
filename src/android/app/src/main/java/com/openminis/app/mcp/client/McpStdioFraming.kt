package com.openminis.app.mcp.client

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * [T-mcp-stdio-frames] MCP stdio framing — port of pi `mcp/src/transports/lines.ts`.
 *
 * A stdio server's stdout carries newline-delimited JSON-RPC (no Content-Length
 * headers); stderr is human diagnostics kept as a bounded tail. The framing
 * layer is separated from [McpStdioClient] so the byte-level contract (16 MiB
 * single-message cap, chunk assembly, incomplete-message reporting) is testable
 * without a process, exactly like pi's `JsonRpcLineReader`.
 *
 * Semantics pinned to pi lines.ts:
 *  - a message may arrive split across any number of chunks; it is assembled
 *    once when its newline arrives (bytes are copied, so callers may reuse
 *    their buffers);
 *  - a complete line over [maxMessageBytes] is reported through [onError] and
 *    dropped — the connection survives for the following messages;
 *  - an accumulated (still-unclosed) message over the cap is dropped as soon
 *    as the running total exceeds the cap, not only at the newline — a server
 *    that never sends a newline cannot grow the buffer unbounded (the old
 *    `BufferedReader.readLine()` path had exactly that unbounded growth);
 *  - one trailing `\r` is stripped (CRLF writers), blank lines are skipped;
 *  - [end] reports a dropped incomplete message when stdout closes mid-frame.
 */
object McpStdioFraming {
    /** pi transport.ts `DEFAULT_MAX_MESSAGE_BYTES` — one JSON-RPC message cap. */
    const val DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024

    /** pi lines.ts `DEFAULT_MAX_STDERR_BYTES` — bounded stderr tail. */
    const val DEFAULT_MAX_STDERR_BYTES = 64 * 1024

    internal val NEWLINE: Byte = '\n'.code.toByte()
}

class McpJsonRpcLineReader(
    private val maxMessageBytes: Int = McpStdioFraming.DEFAULT_MAX_MESSAGE_BYTES,
    private val maxStderrBytes: Int = McpStdioFraming.DEFAULT_MAX_STDERR_BYTES,
    /** Line to JSON. Default: strict org.json parse; unparseable lines report through [onError]. */
    private val parse: (String) -> JSONObject? = { line -> runCatching { JSONObject(line) }.getOrNull() },
    private val onMessage: (JSONObject) -> Unit,
    private val onError: (String) -> Unit,
) {
    // readStdout runs on the single reader thread — the pending buffer needs no lock.
    private val pending = ByteArrayOutputStream()

    // stderr may be drained on its own thread while stdout is read — guarded.
    private val stderrTail = StringBuilder()

    /**
     * Takes the next stdout bytes and reports every complete message, or the
     * error parsing it (pi `readStdout`). [len] is the number of valid bytes in
     * [chunk]; bytes beyond [len] are ignored.
     */
    fun readStdout(chunk: ByteArray, len: Int) {
        var start = 0
        while (true) {
            var newline = -1
            var i = start
            while (i < len) {
                if (chunk[i] == McpStdioFraming.NEWLINE) {
                    newline = i
                    break
                }
                i++
            }
            if (newline < 0) {
                if (start < len) {
                    pending.write(chunk, start, len - start)
                    if (pending.size() > maxMessageBytes) {
                        pending.reset()
                        onError("MCP stdio message exceeds $maxMessageBytes bytes")
                    }
                }
                return
            }
            val line: ByteArray = if (pending.size() > 0) {
                pending.write(chunk, start, newline - start)
                val assembled = pending.toByteArray()
                pending.reset()
                assembled
            } else {
                chunk.copyOfRange(start, newline)
            }
            start = newline + 1
            if (line.size > maxMessageBytes) {
                onError("MCP stdio message exceeds $maxMessageBytes bytes")
                continue
            }
            val text = String(line, 0, line.size, StandardCharsets.UTF_8).removeSuffix("\r")
            if (text.isBlank()) continue
            val obj = parse(text)
            if (obj == null) {
                onError("MCP stdio message is not valid JSON: ${text.take(120)}")
                continue
            }
            onMessage(obj)
        }
    }

    /** Takes the next stderr bytes into the bounded tail (pi `readStderr`). */
    fun readStderr(chunk: ByteArray, len: Int) {
        synchronized(stderrTail) {
            stderrTail.append(String(chunk, 0, len, StandardCharsets.UTF_8))
            val over = stderrTail.length - maxStderrBytes
            if (over > 0) {
                stderrTail.delete(0, over)
                // The char-wise delete can land inside a surrogate pair; drop
                // one more leading char so the snapshot never starts with an
                // orphan low surrogate (adversarial review P2-9).
                if (stderrTail.isNotEmpty() && Character.isLowSurrogate(stderrTail[0])) {
                    stderrTail.deleteCharAt(0)
                }
            }
        }
    }

    /** The last [maxStderrBytes] of stderr — "why did it die" context for errors. */
    fun stderrSnapshot(): String = synchronized(stderrTail) { stderrTail.toString() }

    /**
     * Ends stdout: a message without its newline is reported as an error and
     * dropped (pi `end()`).
     */
    fun end() {
        // ByteArrayOutputStream.toString(Charset) is API 33+ on Android — go
        // through the bytes so every supported release takes this path.
        val leftover = String(pending.toByteArray(), StandardCharsets.UTF_8).trim()
        pending.reset()
        if (leftover.isNotEmpty()) {
            onError("MCP stdio server closed with an incomplete JSON-RPC message")
        }
    }
}
