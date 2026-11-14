package com.openminis.app.agent

import android.content.Context
import com.openminis.app.tools.AtomicFileWrite
import java.io.File
import java.io.IOException

/**
 * [T-persona-session-override] Session-scoped persona override storage,
 * split from [PersonaPromptLibrary] (the library file was over its
 * architecture ceiling; this pair is a self-contained read/write surface
 * over `sessions/<id>/memory/PERSONA.md`).
 *
 * Resolution precedence lives in [PersonaPromptLogic.resolveIdWithSource]:
 * session override > provider selection > global selection > builtin.
 */
internal object PersonaSessionOverride {

    fun read(context: Context, sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null
        val file = PersonaPromptLibrary.sessionPersonaFile(context, sessionId)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * Write — or clear when [body] is blank. Atomic like every prompt-source
     * write: a torn read drops the persona for exactly one turn.
     */
    fun write(context: Context, sessionId: String, body: String) {
        val file = PersonaPromptLibrary.sessionPersonaFile(context, sessionId)
        if (body.isBlank()) {
            file.delete()
            return
        }
        // AtomicFileWrite: per-path lock + short-write detection + verify;
        // null = the same IOException the old direct writeText path threw.
        if (AtomicFileWrite.write(file, body, temp = File(file.parentFile, "${file.name}.tmp")) == null) {
            throw IOException("session override write failed for ${file.name}")
        }
        // [T-persona-revision-bump] Same-(mtime,size) edits kept the OLD
        // persona for a turn — the stat alone is not a fingerprint bump.
        PersonaPromptLibrary.bumpLocked()
    }
}
