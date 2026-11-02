package com.openminis.app.a2a

import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-a2a-glibc] Wraps every provider-plugin entry binary in a launcher
 * shell script that execs it under the app-bundled glibc runtime.
 *
 * Upstream ships its provider packs as upstream-built glibc binaries
 * (`aarch64-unknown-linux-gnu`); Android's bionic neither loads glibc
 * binaries nor has an `/etc` for their DNS/zoneinfo files. The app bundles
 * a self-contained glibc runtime (loader + libc + zoneinfo, built by
 * `scripts/build_a2a_glibc.sh` with the compile-time `/etc` paths
 * retargeted into the private home), so each plugin entry becomes:
 *
 * ```
 * #!/system/bin/sh
 * exec <home>/glibc/ld-linux-aarch64.so.1 --library-path <home>/glibc \
 *      --inhibit-cache "<entry>.gnu" "$@"
 * ```
 *
 * The real binary is renamed `<entry>.gnu`; the gateway keeps addressing
 * `entry` (its registry/manifest entry, `ensure_entry_executable`, spawn,
 * health and log capture all key on that name), so the whole plugin
 * lifecycle is untouched by the wrapper — only the exec target changed.
 *
 * Two places re-run [ensure] idempotently: installation (right after the
 * packs land, so the first boot needs no extra pass) and gateway start
 * (the panel can install a pack itself while the app was away — the next
 * start wraps it before any spawn). A pack whose entry is neither wrapped
 * nor a plain ELF is left alone with a log line: the gateway's own
 * `start_pack` will fail it with a clear reason instead of us guessing.
 */
object A2aPluginShell {
    private const val TAG = "A2aPluginShell"

    /** Suffix the real glibc binary gets when the script takes its name. */
    internal const val GNU_SUFFIX = ".gnu"

    /** The glibc loader name inside the deployed `<home>/glibc/` dir. */
    internal const val LOADER = "ld-linux-aarch64.so.1"

    /**
     * Wrap every not-yet-wrapped plugin entry under
     * `<home>/data/plugins/<id>/<version>/`. Idempotent and per-pack
     * fault-isolated: one bad directory is logged and the rest still run.
     */
    fun ensure(home: File) {
        val plugins = File(home, "data/plugins")
        if (!plugins.isDirectory) return
        val glibc = File(home, "glibc")
        if (!File(glibc, LOADER).isFile) {
            AppLogger.warning(TAG, "glibc runtime missing at ${glibc.path} — plugins left unwrapped")
            return
        }
        for (idDir in plugins.listFiles().orEmpty()) {
            if (!idDir.isDirectory) continue
            for (versionDir in idDir.listFiles().orEmpty()) {
                if (!versionDir.isDirectory) continue
                wrapEntry(entryFor(versionDir), home)
            }
        }
    }

    /**
     * The pack's entry binary for this platform, resolved the same way the
     * upstream `entry_for_target` does (loose `aarch64`+`linux` match on the
     * manifest's triple map, exact `aarch64-unknown-linux-gnu` first). Null
     * when the manifest is missing or carries no entry for us — the gateway
     * will fail that pack with its own message, wrapping nothing is right.
     */
    internal fun entryFor(versionDir: File): File? {
        val manifest = File(versionDir, "pack.json")
        if (!manifest.isFile) return null
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        val entryMap = ENTRY_MAP_REGEX.find(text)?.groupValues?.getOrNull(1) ?: return null
        val entries = ENTRY_REGEX.findAll(entryMap).associate {
            it.groupValues[1] to it.groupValues[2]
        }
        val rel = entries["aarch64-unknown-linux-gnu"]
            ?: entries.entries.firstOrNull { (triple, _) ->
                triple.contains("aarch64") && triple.contains("linux")
            }?.value
            ?: return null
        return File(versionDir, rel)
    }

    /**
     * Wrap one entry when it is still a raw glibc ELF; a wrapped entry
     * (script present, `.gnu` sibling present) passes through untouched.
     * Returns true when the entry is (or already was) in wrapped state.
     */
    internal fun wrapEntry(entry: File?, home: File): Boolean {
        if (entry == null || !entry.isFile) return false
        val gnu = File(entry.path + GNU_SUFFIX)
        if (gnu.isFile) {
            // Wrapped before: the script already owns the entry name.
            if (!isElf(entry)) return true
            // Half-wrapped: the rename landed but the script write never
            // did (the app died between the two steps). The binary is safe
            // in `.gnu` — rewrite the script to heal. Answering false here
            // (the old shape) left the raw 0644 ELF in place, so the
            // gateway spawned it straight into EACCES on every boot with
            // no recovery path short of a reinstall.
            AppLogger.warning(TAG, "healing half-wrapped entry: ${entry.path}")
            return try {
                writeScript(entry, home, gnu)
                true
            } catch (t: Throwable) {
                // No rollback: the binary lives in `.gnu`, the next ensure
                // pass simply retries the script write.
                AppLogger.warning(TAG, "heal failed for ${entry.path}: ${t.message}")
                false
            }
        }
        if (!isElf(entry)) {
            // Not a raw glibc binary and not wrapped by us — e.g. a pack
            // the gateway installed that ships its own launcher. Leave it.
            AppLogger.info(TAG, "entry not a plain ELF, left as-is: ${entry.path}")
            return true
        }
        if (!entry.renameTo(gnu)) {
            AppLogger.warning(TAG, "cannot rename entry for wrapping: ${entry.path}")
            return false
        }
        return try {
            writeScript(entry, home, gnu)
            true
        } catch (t: Throwable) {
            // Roll the binary back so the pack stays exactly as shipped.
            entry.delete()
            gnu.renameTo(entry)
            AppLogger.warning(TAG, "wrap failed, rolled back ${entry.path}: ${t.message}")
            false
        }
    }

    /**
     * Writes the launcher script over the entry name and marks it +x.
     * Throws on failure so each caller picks its own recovery: a first
     * wrap rolls the binary back, a half-wrapped heal is simply retried.
     */
    private fun writeScript(entry: File, home: File, gnu: File) {
        val loader = File(File(home, "glibc"), LOADER).path
        val script = shellScript(loader, File(home, "glibc").path, gnu.path)
        entry.writeText(script)
        entry.setExecutable(true, false)
        AppLogger.info(TAG, "wrapped glibc plugin: ${entry.path}")
    }

    /**
     * The launcher script text. Pure — tests pin it byte-for-byte.
     * `--inhibit-cache` keeps a stale ld.so.cache out of the resolution;
     * `exec` replaces the shell so the plugin keeps its own pid/argv.
     */
    internal fun shellScript(loader: String, libDir: String, gnuBinary: String): String =
        "#!/system/bin/sh\n" +
            "exec \"$loader\" --library-path \"$libDir\" --inhibit-cache \"$gnuBinary\" \"\$@\"\n"

    /** ELF magic check (0x7f 'E' 'L' 'F'). */
    internal fun isElf(file: File): Boolean {
        val head = runCatching {
            file.inputStream().use { it.readNBytes(4) }
        }.getOrNull() ?: return false
        return head.size == 4 && head[0] == 0x7f.toByte() &&
            head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
            head[3] == 'F'.code.toByte()
    }

    // `"entry": { "aarch64-unknown-linux-gnu": "bin/…", … }` — the manifest
    // shape the upstream server reads with the same loose matching.
    private val ENTRY_MAP_REGEX = Regex("\"entry\"\\s*:\\s*\\{([^}]*)\\}")
    private val ENTRY_REGEX = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
}
