package com.openminis.app.sandbox

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-shell-env-writer] Env/PATH writers extracted verbatim from
 * PersistentShell (mechanical split; the shell file was over the 800-line
 * new-file cap). Both suspend on the shell's stdin writer and are no-ops
 * when the shell is not alive.
 */
internal object ShellEnvWriter {

    /**
     * Push env-var changes into the running shell: `unset` for keys the user
     * has removed, `export` for new/changed ones. Illegal key names are
     * logged and skipped (a crafted key would execute shell syntax).
     */
    suspend fun applyEnvironment(
        shell: PersistentShell,
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        if (!shell.isAlive) return
        val writer = shell.stdinWriterOrNull() ?: return
        withContext(Dispatchers.IO) {
            try {
                synchronized(shell.stdinWriteLock) {
                    for (key in previousKeys - envVars.keys) {
                        // Keys reach the shell verbatim inside `unset`/`export`
                        // lines; a crafted key would execute shell syntax, so
                        // illegal names are logged and skipped, not sanitized.
                        if (!TerminalSession.ENV_KEY.matches(key)) {
                            Log.w(TAG, "applyEnvironment: skipping illegal env key '${key.take(32)}'")
                            continue
                        }
                        writer.write("unset $key\n")
                    }
                    for ((key, value) in envVars) {
                        if (!TerminalSession.ENV_KEY.matches(key)) {
                            Log.w(TAG, "applyEnvironment: skipping illegal env key '${key.take(32)}'")
                            continue
                        }
                        // Escape single quotes in values
                        val escaped = value.replace("'", "'\\''")
                        writer.write("export $key='$escaped'\n")
                    }
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply env vars: ${e.message}")
            }
        }
    }

    /** Refresh PATH inside the guest from the current rootfs layout. */
    suspend fun refreshPath(shell: PersistentShell, context: android.content.Context) {
        if (!shell.isAlive) return
        val writer = shell.stdinWriterOrNull() ?: return
        withContext(Dispatchers.IO) {
            try {
                synchronized(shell.stdinWriteLock) {
                    writer.write(PersistentShellEnvironment.pathRefreshCommand(
                        RootfsManager.getInstance(context).currentGuestPath()))
                    writer.flush()
                }
            } catch (e: Exception) { Log.w(TAG, "PATH refresh failed: ${e.message}") }
        }
    }

    private const val TAG = "PersistentShell"
}
