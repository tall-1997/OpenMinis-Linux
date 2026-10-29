package com.openminis.app.a2a

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Deploys the A2A gateway on the host.
 *
 * The gateway ships its runtime binary inside the APK
 * ([A2aGatewayProcess.BINARY] in nativeLibraryDir — Android package
 * extraction only unpacks `lib*.so`, hence the extension), so "install" is
 * not a guest operation at all: it is a host-side deployment of the panel
 * bundle from APK assets into the app-private home
 * ([A2aGatewayProcess.homeDir]), plus the state dirs the binary expects.
 * No sandbox session, no Node, no PRoot — the guest path was a compatibility
 * shim for a binary that runs perfectly well as an app process.
 *
 * Moved from `plugins/builtin/NativeGatewayInstaller.kt` when a2a left the
 * builtin plugin catalog for this package. The deployment is atomic in the
 * same spirit as the guest install.sh: the bundle unzips into a staging dir
 * and swaps in with a rename, so a crash mid-deploy can never leave a half
 * panel next to a live gateway. Zero semantic change to any of that.
 *
 * Progress used to stream into the builtin-plugin store so the market card
 * could render it; a2a is decoupled from that system now — [install]
 * returns null-or-reason and [A2aGateway] maps it onto
 * [A2aStatus.installed] / [A2aStatus.lastError].
 */
object A2aInstaller {

    /**
     * Panel bundle inside APK assets. The path is kept from the builtin era
     * (the zip lives under assets/builtin_plugins/) — only the owning code
     * moved.
     */
    internal const val PAYLOAD_ASSET = "builtin_plugins/a2a-ui.zip"

    /** Upstream payload version, pinned in CI's native-deps workflow (A2A_TAG). */
    internal const val VERSION = "3.0.8"

    /**
     * True when the home layout is complete: `ui/index.html` present and the
     * native binary actually present in the APK's nativeLibraryDir. Install
     * is atomic ([install] swaps `ui/` in with a rename), so `ui/index.html`
     * on disk is the deploy-success marker; the binary check catches an APK
     * built without the native-deps artifact.
     */
    fun isDeployed(context: Context): Boolean {
        if (!binaryPresent(context)) return false
        val home = A2aGatewayProcess.homeDir(context.filesDir.path)
        return File(home, "ui/index.html").isFile
    }

    private fun binaryPresent(context: Context): Boolean {
        val bin = A2aGatewayProcess.binary(context)
        return bin.isFile && bin.canExecute()
    }

    /**
     * Deploys the panel bundle. Returns null when the home layout is
     * complete: `ui/index.html` present, `data/` and `data/tmp/` writable,
     * and the native binary actually present in the APK's nativeLibraryDir
     * (a missing binary means the APK was built without the native-deps
     * artifact — fail loudly, not at first launch). Non-null is the failure
     * reason [A2aGateway] surfaces as [A2aStatus.lastError]. Blocking
     * (an 8 MB unzip) — call off the main thread.
     */
    fun install(context: Context): String? {
        try {
            if (!binaryPresent(context)) {
                return "native binary missing from APK: ${A2aGatewayProcess.BINARY}"
            }

            val home = A2aGatewayProcess.homeDir(context.filesDir.path)
            val ui = File(home, "ui")
            val staging = File(home, ".staging")
            staging.deleteRecursively()
            staging.mkdirs()

            context.assets.open(PAYLOAD_ASSET).use { zip ->
                ZipInputStream(zip.buffered()).use { stream ->
                    var entry = stream.nextEntry
                    while (entry != null) {
                        val target = deployTarget(staging, entry.name)
                            ?: return@use // zip-slip guard: skip entries outside staging
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            // use{} closes the stream: THIS entry's buffered
                            // tail must reach disk before the next entry
                            // starts. Without it the tail (up to the buffer
                            // size — small files in full) was dropped:
                            // app.js hit disk at 46478 of 51173 bytes
                            // on-device and the panel rendered half-broken.
                            target.outputStream().buffered().use { out ->
                                stream.copyTo(out)
                            }
                        }
                        entry = stream.nextEntry
                    }
                }
            }
            val index = File(staging, "index.html").takeIf { it.isFile }
                ?: File(staging, "ui/index.html").takeIf { it.isFile }
            if (index == null) {
                staging.deleteRecursively()
                return "panel bundle missing index.html"
            }
            // Normalize: the bundle may carry its own top-level ui/ dir.
            val stagedRoot = if (index.parentFile == staging) staging else index.parentFile!!
            // Never publish a bundle whose extraction was cut short (killed
            // writer, short read, skipped entry, a future refactor that
            // reintroduces the unclosed-writer bug): verify the staged tree
            // entry-by-entry against the zip's own size table BEFORE the
            // destructive swap. The asset is re-read so a torn first pass
            // cannot bless itself.
            val problems = context.assets.open(PAYLOAD_ASSET).use { zip ->
                // Verify against the EXTRACTION root: entry names are
                // staging-relative regardless of whether the bundle carries
                // its own top-level ui/ dir (stagedRoot is only about which
                // subtree becomes ui/).
                verifyStagedBundle(staging, expectedSizes(zip.buffered()))
            }
            if (problems.isNotEmpty()) {
                staging.deleteRecursively()
                return "bundle verify failed: " +
                    problems.take(3).joinToString(", ")
            }
            ui.deleteRecursively()
            if (!stagedRoot.renameTo(ui)) {
                staging.deleteRecursively()
                return "panel deploy failed"
            }
            home.resolve(".staging").deleteRecursively()

            val data = File(home, "data")
            data.mkdirs()
            File(data, "tmp").mkdirs()

            return null
        } catch (t: Throwable) {
            return (t.message ?: t.javaClass.simpleName).take(200)
        }
    }

    /**
     * Declared sizes of every file entry in an open zip stream — the
     * extraction's own table of truth. Pure: reads only the entry headers,
     * never the payload. See [verifyStagedBundle].
     */
    internal fun expectedSizes(input: java.io.InputStream): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        ZipInputStream(input).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = entry.size
                entry = stream.nextEntry
            }
        }
        return out
    }

    /**
     * Size-for-size check between the zip entry table [expected] and the
     * files under [stagedRoot]. Every non-directory entry must exist with
     * EXACTLY its declared size — a truncated extraction (unflushed buffer
     * tail, killed writer, skipped entry) fails here, before the destructive
     * swap can publish it. Entries that escaped staging (zip-slip)
     * surface as `missing`. Pure — the JVM test pins the missing/short/
     * zero cases without Robolectric.
     */
    internal fun verifyStagedBundle(
        stagedRoot: File,
        expected: Map<String, Long>,
    ): List<String> {
        val problems = mutableListOf<String>()
        for ((name, size) in expected) {
            val file = File(stagedRoot, name)
            when {
                !file.isFile -> problems += "missing $name"
                file.length() != size -> problems += "short $name (${file.length()}/$size)"
            }
        }
        return problems
    }

    /**
     * Zip-slip guard: resolves [name] under [staging] and returns null for
     * any entry that tries to escape (absolute paths, `..` traversal).
     */
    private fun deployTarget(staging: File, name: String): File? {
        if (name.isBlank()) return null
        val target = File(staging, name)
        val canonical = target.canonicalPath
        if (!canonical.startsWith(staging.canonicalPath + File.separator)) return null
        return target
    }
}
