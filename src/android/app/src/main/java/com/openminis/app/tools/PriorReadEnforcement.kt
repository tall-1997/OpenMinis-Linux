package com.openminis.app.tools

import java.io.File

/**
 * [T-prior-read] Read-before-edit gate, ported from qwen-code
 * tools/priorReadEnforcement.ts (Apache-2.0). An edit or overwrite of an
 * EXISTING file is refused unless this session has read it (or authored it)
 * and the on-disk (mtime,size) fingerprint has not drifted since. It protects
 * against the two hallucination modes that "old_string not found" cannot:
 * the model remembering content it never saw, and editing a file another
 * writer changed underneath it after the model read it.
 *
 * Contract (qwen #3932→#4002): ANY read counts — demanding a full read is a
 * deadlock on huge files. The drift check is the only safety net, and the
 * TextReplacers "0 occurrences" failure catches the residual
 * same-fingerprint-rewrite case. Grep/shell reads do NOT register here — the
 * model may edit a file it has only grepped once it has file_read it.
 *
 * Pure JVM: the stat hook is injectable so tests run without android.system.Os.
 */
object PriorReadEnforcement {
    const val EDIT_REQUIRES_PRIOR_READ = "EDIT_REQUIRES_PRIOR_READ"
    const val FILE_CHANGED_SINCE_READ = "FILE_CHANGED_SINCE_READ"
    const val PRIOR_READ_VERIFICATION_FAILED = "PRIOR_READ_VERIFICATION_FAILED"

    /** @param modelMessage model-facing refusal text (null when ok). */
    data class Decision(val ok: Boolean, val errorCode: String? = null, val modelMessage: String? = null)

    /**
     * Gate for editing/overwriting an EXISTING file. Call only when the target
     * exists (edit's "File not found" and write's create path stay upstream).
     *
     * @param displayPath the guest-side path as the model passed it — refusals
     *   quote it back verbatim, since the model only knows its own spelling.
     * @param stat filesystem hook; defaults to the real lstat-backed
     *   [FileIdentity.of]. Null result or a zero ino fails CLOSED.
     */
    fun checkOverwrite(
        cache: FileReadCache,
        file: File,
        displayPath: String,
        stat: (File) -> FileIdentity? = { FileIdentity.of(it) },
    ): Decision {
        val identity = stat(file)
            ?: return Decision(
                false,
                PRIOR_READ_VERIFICATION_FAILED,
                "Error: could not verify '$displayPath' on disk before editing " +
                    "(stat failed). Re-read it with file_read, then retry; " +
                    "if it persists, operate via shell_execute. " +
                    "[$PRIOR_READ_VERIFICATION_FAILED]",
            )
        if (!identity.verifiable) {
            return Decision(
                false,
                PRIOR_READ_VERIFICATION_FAILED,
                "Error: the filesystem did not report a stable identity for " +
                    "'$displayPath' (ino=0), so same-file tracking is impossible " +
                    "and the edit is refused. Re-read it with file_read and retry " +
                    "once; if it persists, operate via shell_execute. " +
                    "[$PRIOR_READ_VERIFICATION_FAILED]",
            )
        }
        val result = cache.check(identity)
        return when (result.state) {
            ReadCacheState.FRESH ->
                if (result.cacheable) {
                    Decision(true)
                } else {
                    Decision(
                        false,
                        EDIT_REQUIRES_PRIOR_READ,
                        "Error: the last read of '$displayPath' returned a binary/preview " +
                            "snapshot, not text, so it cannot back a text edit. If the " +
                            "file is actually text, read it with file_read (for huge " +
                            "files narrow it with lines/offset, or head/tail via " +
                            "shell_execute first), then retry. [$EDIT_REQUIRES_PRIOR_READ]",
                    )
                }
            ReadCacheState.STALE ->
                Decision(
                    false,
                    FILE_CHANGED_SINCE_READ,
                    "Error: '$displayPath' changed on disk since it was last read " +
                        "(mtime/size drifted — something else wrote to it). Re-read it " +
                        "with file_read and re-apply your change against the current " +
                        "content. [$FILE_CHANGED_SINCE_READ]",
                )
            ReadCacheState.UNKNOWN ->
                Decision(
                    false,
                    EDIT_REQUIRES_PRIOR_READ,
                    "Error: '$displayPath' has not been read in this session, so editing " +
                        "it would be editing from memory. Read it with file_read first " +
                        "(partial line windows count), then retry. [$EDIT_REQUIRES_PRIOR_READ]",
                )
            ReadCacheState.UNVERIFIABLE ->
                Decision(
                    false,
                    PRIOR_READ_VERIFICATION_FAILED,
                    "Error: same-file tracking for '$displayPath' is impossible on this " +
                        "filesystem (ino=0), so the edit is refused. Re-read it with " +
                        "file_read and retry once; if it persists, operate via " +
                        "shell_execute. [$PRIOR_READ_VERIFICATION_FAILED]",
                )
        }
    }
}
