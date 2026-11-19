package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputPolicyTest {

    private val sensitive = listOf("sk-Ab12Cd34Ef56Gh78")

    /**
     * [T-tool-output-single-mechanism] There is no `workspaceRoot` to redirect any
     * more: the oversized-output path is [ToolOutputSpill]'s per-session one and
     * an apply() call without a Context/session cannot spill at all — it must
     * still come back CAPPED. These tests therefore exercise exactly that
     * no-file-available branch, which is hermetic on any machine (the old test
     * only passed because it pointed a GUEST path at a temp dir).
     */

    @Test
    fun plainTextReplace() {
        val out = ToolOutputPolicy.redactWith("token=sk-Ab12Cd34Ef56Gh78 done", sensitive)
        assertEquals("token=[REDACTED] done", out)
    }

    @Test
    fun jsonObjectValueRedacted() {
        val out = ToolOutputPolicy.redactWith(
            """{"apiKey":"sk-Ab12Cd34Ef56Gh78","ok":true}""",
            sensitive,
        )
        assertEquals("""{"apiKey":"[REDACTED]","ok":true}""", out)
    }

    @Test
    fun jsonEscapedValueRedacted() {
        // 值经 JSON 转义（引号逃逸）后纯文本 replace 匹配不到原文，
        // 结构化递归仍能命中。
        val out = ToolOutputPolicy.redactWith(
            """{"cmd":"echo \"sk-Ab12Cd34Ef56Gh78\" > /tmp/x"}""",
            sensitive,
        )
        assertEquals("""{"cmd":"echo \"[REDACTED]\" > /tmp/x"}""", out)
    }

    @Test
    fun jsonNestedArrayRedacted() {
        val out = ToolOutputPolicy.redactWith(
            """{"env":[{"k":"A","v":"sk-Ab12Cd34Ef56Gh78"},{"k":"B","v":"x"}]}""",
            sensitive,
        )
        val parsed = org.json.JSONObject(out)
        val arr = parsed.getJSONArray("env")
        assertEquals("[REDACTED]", arr.getJSONObject(0).getString("v"))
        assertEquals("x", arr.getJSONObject(1).getString("v"))
    }

    @Test
    fun nonJsonFallsBackToTextReplace() {
        val out = ToolOutputPolicy.redactWith("line1\nsk-Ab12Cd34Ef56Gh78\nline3", sensitive)
        assertEquals("line1\n[REDACTED]\nline3", out)
    }

    @Test
    fun emptySensitiveListIsNoop() {
        val text = """{"apiKey":"sk-Ab12Cd34Ef56Gh78"}"""
        assertEquals(text, ToolOutputPolicy.redactWith(text, emptyList()))
    }

    @Test
    fun redactionPreservesUnchangedJsonAndTextAfterAJsonPrefix() {
        val unchanged = "{ \"a\": [1, 2] }\n"
        assertEquals(unchanged, ToolOutputPolicy.redactWith(unchanged, sensitive))
        val log = "{ \"start\": true }\nkey=${sensitive.single()}\nend"
        assertEquals("{ \"start\": true }\nkey=[REDACTED]\nend", ToolOutputPolicy.redactWith(log, sensitive))
    }

    @Test
    fun belowThresholdNoSpill() {
        val text = "a".repeat(ToolOutputSpill.LIMIT - 1)
        val out = ToolOutputPolicy.apply(text, "test1")
        assertEquals(text.length, out.length)
        assertEquals(text, out)
    }

    @Test
    fun overThresholdIsCappedToHeadTailPreview() {
        val half = ToolOutputSpill.PREVIEW_BODY_CHARS / 2
        val text = "a".repeat(half) + "m".repeat(25_000) + "b".repeat(half)
        val out = ToolOutputPolicy.apply(text, "test2")
        assertTrue(out.startsWith("[tool-output-spill]"))
        assertEquals("a".repeat(half) + "\n\n…(25000 chars omitted)…\n\n" + "b".repeat(half), ToolOutputSpill.previewBody(out))
    }

    /**
     * [T-tool-output-single-mechanism] The bug this pins: `apply()` used to
     * `return text` — the UNCAPPED body — whenever its own spill write failed
     * (which was ALWAYS, because it wrote to the guest path
     * `/var/minis/workspace/tool_outputs`). A session-less caller must still get
     * a bounded preview.
     */
    @Test
    fun capHoldsEvenWhenNoSpillIsPossible() {
        val text = "x".repeat(200_000)
        val out = ToolOutputPolicy.apply(text, "no-session")
        assert(out.length < 12_000) { "uncapped body reached the model: ${out.length} chars" }
        assert(out.contains("could not be saved")) { "preview must admit there is no spill file" }
        assert(!out.contains("minis://workspace")) { "must not point at a file that does not exist" }
    }

    @Test
    fun emojiBoundarySafe() {
        // Emojis would be split if we used raw char-index slicing.
        val emoji = "\uD83D\uDE00" // 😀 surrogate pair
        val text = (emoji + "prefix_").repeat(5000) + "suffix_" + (emoji + "end_").repeat(5000)
        val out = ToolOutputPolicy.apply(text, "emoji-test")
        assert(!hasOrphanSurrogate(out)) { "Should not contain orphaned surrogate halves" }
    }

    private fun hasOrphanSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                // 低代理前面必须是高代理。
                if (i == 0 || !Character.isHighSurrogate(s[i - 1])) return true
                i++
            } else {
                i++
            }
        }
        return false
    }

    @Test
    fun spillIsCompleteRedactedContent() {
        // Redaction happens BEFORE the cap, so what reaches the preview — and, on
        // the session path, the spill FILE the model can later file_read — is the
        // redacted text. apply() hardwires that order:
        //   cappedPreview(name, redactEnvVars(output)).
        // redactEnvVars() only knows values that are actually in the process
        // environment, so the deterministic half of this runs against the
        // injectable seam (redactWith) composed with the same preview builder the
        // cap uses, and the end-to-end half runs whenever the host really does
        // carry a redactable value (see pickRedactableEnvValue).
        val original = "Start sk-Ab12Cd34Ef56Gh78 middle " + "b".repeat(25_000)
        val out = ToolOutputSpill.cappedPreview(
            "spill-complete-test",
            ToolOutputPolicy.redactWith(original, sensitive),
        )
        assert(!out.contains("sk-Ab12Cd34Ef56Gh78")) { "Should not contain original key" }
        assert(out.contains("[REDACTED]")) { "Should contain [REDACTED]" }
        // Same shape apply() produces for an oversized session-less result.
        val applied = ToolOutputPolicy.apply(original, "spill-complete-test")
        assertTrue(applied.startsWith("[tool-output-spill]"))
        assertTrue(applied.contains("middle"))
    }

    /**
     * End-to-end through [ToolOutputPolicy.apply] with a value the redactor can
     * actually see. Skipped when the host environment has nothing matching the
     * redaction predicate (6+ chars with both letters and digits) — a skip is
     * honest, a hard-coded fake secret silently pinned nothing.
     */
    @Test
    fun applyRedactsBeforeCapping() {
        val candidate = pickRedactableEnvValue()
        org.junit.Assume.assumeTrue(
            "no process env value matches the redaction predicate on this host",
            candidate != null,
        )
        val secret = candidate ?: return
        val text = "head $secret tail " + "c".repeat(20_000)
        val out = ToolOutputPolicy.apply(text, "env-redact")
        assert(!out.contains(secret)) { "secret survived into the preview" }
        assert(out.contains("[REDACTED]")) { "preview shows no redaction at all" }
    }

    /** An env value [ToolOutputPolicy.redactEnvVars] would replace, or null. */
    private fun pickRedactableEnvValue(): String? =
        runCatching { System.getenv().values }.getOrNull()
            ?.firstOrNull { it.length >= 6 && it.any(Char::isLetter) && it.any(Char::isDigit) }

    @Test
    fun existingJsonRecursiveTestsStillGreen() {
        // Re-run existing tests to ensure they still pass
        val sensitive = listOf("sk-Ab12Cd34Ef56Gh78")
        
        val out1 = ToolOutputPolicy.redactWith("token=sk-Ab12Cd34Ef56Gh78 done", sensitive)
        assertEquals("token=[REDACTED] done", out1)
        
        val out2 = ToolOutputPolicy.redactWith(
            """{"apiKey":"sk-Ab12Cd34Ef56Gh78","ok":true}""",
            sensitive,
        )
        assertEquals("""{"apiKey":"[REDACTED]","ok":true}""", out2)
        
        val out3 = ToolOutputPolicy.redactWith(
            """{"cmd":"echo \"sk-Ab12Cd34Ef56Gh78\" > /tmp/x"}""",
            sensitive,
        )
        assertEquals("""{"cmd":"echo \"[REDACTED]\" > /tmp/x"}""", out3)
    }

    @Test
    fun depthCapFlattensAndStillRedacts() {
        // [T-redact-depth] Past MAX_REDACT_DEPTH the subtree is flattened to
        // text and plain-text redacted. The first cut returned the raw node
        // past the cap, so a secret nested one level too deep reached the
        // model unredacted — a depth bound must not double as a leak channel.
        var json = """{"k":"sk-Ab12Cd34Ef56Gh78"}"""
        repeat(40) { json = """{"n":$json}""" }
        val out = ToolOutputPolicy.redactWith(json, sensitive)
        assert(!out.contains("sk-Ab12Cd34Ef56Gh78")) { "secret leaked past the depth cap" }
        assert(out.contains("[REDACTED]")) { "flattened subtree was not redacted" }
    }

    @Test
    fun boundaryAwareRedactionSparesIdentifiers() {
        // [T-redact-boundary] Whole-substring replace rewrote ANY text that
        // happened to contain the value — a token-shaped string glued inside
        // a longer identifier is not the secret and must survive verbatim.
        assertEquals(
            "mysk-Ab12Cd34Ef56Gh78var",
            ToolOutputPolicy.redactWith("mysk-Ab12Cd34Ef56Gh78var", sensitive),
        )
        // Delimited occurrences still redact (punctuation boundaries count).
        assertEquals(
            "key=[REDACTED];done",
            ToolOutputPolicy.redactWith("key=sk-Ab12Cd34Ef56Gh78;done", sensitive),
        )
    }
}
