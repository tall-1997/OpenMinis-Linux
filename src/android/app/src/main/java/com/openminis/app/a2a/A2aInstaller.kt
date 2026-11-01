package com.openminis.app.a2a

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Deploys the A2A gateway on the host.
 *
 * The gateway ships its runtime binary inside the APK
 * ([A2aGatewayProcess.BINARY] in nativeLibraryDir — Android package
 * extraction only unpacks `lib*.so`, hence the extension), so "install" is
 * not a guest operation at all: it is a host-side deployment from APK
 * assets into the app-private home ([A2aGatewayProcess.homeDir]) of
 *
 *  - the panel bundle (`ui/` — the gateway's own web panel, webroot of the
 *    `AGENT2API_PANEL_PORT` listener),
 *  - the glibc runtime (`glibc/` — loader + libc + zoneinfo, deployed by
 *    [A2aGlibcDeploy] and exec'd by [A2aPluginShell]'s wrappers),
 *  - the two upstream provider packs (`data/plugins/<id>/<version>/` — the
 *    engine's plugin home under `AGENT2API_PROXY_HOME`, plus the
 *    `plugins.json` registry the engine's plugin host reads at boot),
 *
 * plus the state dirs and the static `/etc` files (`hosts`,
 * `nsswitch.conf`) the bundled glibc reads from `data/` — its compile-time
 * paths are retargeted there (see scripts/a2a-glibc). No sandbox session,
 * no Node, no PRoot — every piece runs as an app process or an exec of the
 * bundled loader.
 *
 * Moved from `plugins/builtin/NativeGatewayInstaller.kt` when a2a left the
 * builtin plugin catalog for this package. The deployment stays atomic in
 * the same spirit as the guest install.sh: every payload extracts into a
 * staging dir, is verified entry-by-entry against its own size table, and
 * swaps in with a rename — a crash mid-deploy can never leave a half panel
 * or a torn libc next to a live gateway.
 *
 * The registry write merges instead of replacing: packs whose id this
 * app does not own (installed through the panel itself) keep their
 * records, so an app reinstall/repair pass cannot silently unregister
 * them.
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

    /** Bundled glibc runtime tarball (built by scripts/build_a2a_glibc.sh). */
    internal const val GLIBC_ASSET = "a2a-glibc.tar.gz"

    /** Upstream provider pack zips (staged by CI's native-deps workflow). */
    internal const val PROVIDERS_ASSET = "a2a-providers.zip"
    internal const val WEB_PACK_ASSET = "a2a-web-providers.zip"

    /**
     * Upstream payload version, pinned in CI's native-deps workflow
     * (A2A_TAG). The pack dirs and registry records all carry this version,
     * so an app upgrade with a new tag installs alongside and re-registers.
     */
    internal const val VERSION = "3.3.0-beta.6"

    /** Upstream pack ids — the engine keys ownership on them. */
    internal const val PROVIDER_PACK_ID = "agent2api-providers"
    internal const val WEB_PACK_ID = "agent2api-web-providers"

    /**
     * True when the home layout is complete: panel + glibc runtime + both
     * plugin packs + registry all present, and the native binary actually
     * present in the APK's nativeLibraryDir. Install is atomic ([install]
     * swaps each piece in with a rename), so the on-disk markers are
     * deploy-success markers; the binary check catches an APK built without
     * the native-deps artifact.
     */
    fun isDeployed(context: Context): Boolean {
        if (!binaryPresent(context)) return false
        val home = A2aGatewayProcess.homeDir(context.filesDir.path)
        if (!File(home, "ui/index.html").isFile) return false
        if (!File(home, "glibc/${A2aPluginShell.LOADER}").isFile) return false
        if (!packVersionDir(home, PROVIDER_PACK_ID).isDirectory) return false
        if (!packVersionDir(home, WEB_PACK_ID).isDirectory) return false
        return File(home, "data/plugins/plugins.json").isFile
    }

    private fun binaryPresent(context: Context): Boolean {
        val bin = A2aGatewayProcess.binary(context)
        return bin.isFile && bin.canExecute()
    }

    internal fun packVersionDir(home: File, id: String): File =
        File(File(File(home, "data/plugins"), id), VERSION)

    /**
     * Deploys the full gateway layout. Returns null when the home layout is
     * complete (everything [isDeployed] checks), non-null with the failure
     * reason otherwise — [A2aGateway] surfaces it as
     * [A2aStatus.lastError]. Blocking (panel unzip + runtime tar + two pack
     * zips, tens of MB total) — call off the main thread.
     */
    fun install(context: Context): String? {
        try {
            if (!binaryPresent(context)) {
                return "native binary missing from APK: ${A2aGatewayProcess.BINARY}"
            }
            val home = A2aGatewayProcess.homeDir(context.filesDir.path)

            deployPanelBundle(context, home)?.let { return it }
            deployGlibcRuntime(context, home)?.let { return it }
            val packEntry = deployPluginPack(context, home, PROVIDERS_ASSET, PROVIDER_PACK_ID)
                ?: return "provider pack deploy failed"
            val webEntry = deployPluginPack(context, home, WEB_PACK_ASSET, WEB_PACK_ID)
                ?: return "web pack deploy failed"
            writeRegistry(home, packEntry, webEntry)?.let { return it }

            val data = File(home, "data")
            data.mkdirs()
            File(data, "tmp").mkdirs()
            A2aDns.ensureStatic(context)
            // Wrap now: first boot needs no extra pass before the first spawn.
            A2aPluginShell.ensure(home)

            return null
        } catch (t: Throwable) {
            return (t.message ?: t.javaClass.simpleName).take(200)
        }
    }

    /** Registry record written for an installed pack. */
    internal data class PackRecord(val id: String, val version: String, val entry: String)

    // ── panel bundle ────────────────────────────────────────────────────

    private fun deployPanelBundle(context: Context, home: File): String? {
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
            return "bundle verify failed: " + problems.take(3).joinToString(", ")
        }
        ui.deleteRecursively()
        if (!stagedRoot.renameTo(ui)) {
            staging.deleteRecursively()
            return "panel deploy failed"
        }
        staging.deleteRecursively()
        return null
    }

    // ── glibc runtime ───────────────────────────────────────────────────

    private fun deployGlibcRuntime(context: Context, home: File): String? {
        val staging = File(home, ".glibc-staging")
        staging.deleteRecursively()
        staging.mkdirs()

        // The tarball may pack its own top-level dir (`tar czf x.tgz out/`
        // prefixes every entry) — normalize so the runtime lands flat in
        // <home>/glibc/ regardless of how the build script packed it. The
        // dry parse's RAW names decide the shared prefix; extract and
        // verify must both use the same normalized view.
        val raw = context.assets.open(GLIBC_ASSET).use { zip ->
            A2aGlibcDeploy.expectedEntries(zip)
        }
        val topDir = A2aGlibcDeploy.topDirOf(raw.keys)
        val expected = raw.mapKeys { (name, _) -> A2aGlibcDeploy.deployName(name, topDir) }
        val problems = context.assets.open(GLIBC_ASSET).use { zip ->
            A2aGlibcDeploy.extract(zip, staging, topDir)
        } + A2aGlibcDeploy.verify(staging, expected)
        if (problems.isNotEmpty()) {
            staging.deleteRecursively()
            return "glibc deploy failed: " + problems.take(3).joinToString(", ")
        }
        val glibc = File(home, "glibc")
        glibc.deleteRecursively()
        if (!staging.renameTo(glibc)) {
            staging.deleteRecursively()
            return "glibc deploy failed"
        }
        return null
    }

    // ── plugin packs ────────────────────────────────────────────────────

    /**
     * Deploy one provider pack zip into `data/plugins/<id>/<version>/`
     * (staging + verify + rename, like the panel) and return the registry
     * record to write. The entry is read from the pack's own `pack.json`
     * with the engine's loose triple matching ([A2aPluginShell.entryFor]) —
     * never hardcoded, so a pack that renames its binary stays deployable.
     */
    private fun deployPluginPack(
        context: Context,
        home: File,
        asset: String,
        id: String,
    ): PackRecord? {
        val pluginsRoot = File(home, "data/plugins")
        val staging = File(pluginsRoot, ".staging-$id")
        staging.deleteRecursively()
        staging.mkdirs()

        context.assets.open(asset).use { zip ->
            ZipInputStream(zip.buffered()).use { stream ->
                var entry = stream.nextEntry
                while (entry != null) {
                    val target = deployTarget(staging, entry.name)
                        ?: return@use
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out -> stream.copyTo(out) }
                    }
                    entry = stream.nextEntry
                }
            }
        }
        val problems = context.assets.open(asset).use { zip ->
            verifyStagedBundle(staging, expectedSizes(zip.buffered()))
        }
        if (problems.isNotEmpty()) {
            staging.deleteRecursively()
            AppLogger.warning("A2aInstaller", "pack $id verify failed: $problems")
            return null
        }
        // The staging root may be <id>/<version>/ (official packs) — find
        // the dir carrying pack.json, that subtree is the version dir.
        val versionRoot = findManifestDir(staging) ?: run {
            staging.deleteRecursively()
            AppLogger.warning("A2aInstaller", "pack $id missing pack.json")
            return null
        }
        val entry = A2aPluginShell.entryFor(versionRoot)?.relativeTo(versionRoot)?.path ?: run {
            staging.deleteRecursively()
            AppLogger.warning("A2aInstaller", "pack $id missing entry for aarch64 linux")
            return null
        }
        val targetVersion = packVersionDir(home, id)
        targetVersion.parentFile?.deleteRecursively() // previous versions
        targetVersion.parentFile?.mkdirs()
        if (!versionRoot.renameTo(targetVersion)) {
            staging.deleteRecursively()
            AppLogger.warning("A2aInstaller", "pack $id rename failed")
            return null
        }
        staging.deleteRecursively()
        return PackRecord(id, VERSION, entry)
    }

    private fun findManifestDir(staging: File): File? {
        if (File(staging, "pack.json").isFile) return staging
        return staging.walkTopDown().maxDepth(4)
            .filter { it.isDirectory && File(it, "pack.json").isFile }
            .firstOrNull()
    }

    // ── registry ────────────────────────────────────────────────────────

    /**
     * Write `plugins.json` with the two app-owned packs merged over any
     * existing records: same-id records are replaced (version upgrades),
     * foreign packs keep their exact JSON. Null on success, reason
     * otherwise. Both records carry their entry from their own pack.json
     * ([deployPluginPack]'s entryFor result) — never hardcoded, so a pack
     * that renames its binary stays deployable. The engine's `PackRegistry`
     * deserializes with defaults for
     * every field we omit — only id/version/enabled/entry are load-bearing
     * ([T-a2a-glibc] pins that contract).
     */
    private fun writeRegistry(home: File, providers: PackRecord, web: PackRecord): String? {
        val registryFile = File(home, "data/plugins/plugins.json")
        val existing = registryFile.takeIf { it.isFile }?.readText().orEmpty()
        val foreign = splitPackObjects(existing)
            .filter { objectText -> packIdOf(objectText) !in setOf(providers.id, web.id) }
        val owned = listOf(registryObjectText(providers), registryObjectText(web))
        val text = registryText(owned + foreign)
        val tmp = File(registryFile.path + ".tmp")
        return try {
            tmp.writeText(text)
            if (!tmp.renameTo(registryFile)) {
                tmp.delete()
                "registry write failed"
            } else {
                null
            }
        } catch (t: Throwable) {
            tmp.delete()
            "registry write failed: ${t.message?.take(100)}"
        }
    }

    /** One pack record as registry JSON. Pure — the JVM test pins it. */
    internal fun registryObjectText(record: PackRecord): String {
        return "{\n" +
            "      \"id\": \"${record.id}\",\n" +
            "      \"version\": \"${record.version}\",\n" +
            "      \"enabled\": true,\n" +
            "      \"providers\": {},\n" +
            "      \"entry\": \"${record.entry}\",\n" +
            "      \"sha256\": \"\",\n" +
            "      \"signed\": false,\n" +
            "      \"installed_at\": ${System.currentTimeMillis()},\n" +
            "      \"manifest_providers\": []\n" +
            "    }"
    }

    /** Assemble the full registry document from pack object texts. */
    internal fun registryText(packObjects: List<String>): String =
        "{\n  \"packs\": [\n" +
            packObjects.joinToString(",\n") { "    $it" } +
            "\n  ]\n}\n"

    /**
     * Split a registry document's `packs` array into its object texts via
     * brace/string balancing — the registry merges foreign packs by
     * re-emission, so the parser must be exact, not regex-shaped. Pure —
     * the JVM test pins nesting, quoted braces and escapes.
     */
    internal fun splitPackObjects(json: String): List<String> {
        val arrayStart = json.indexOf("\"packs\"")
        if (arrayStart < 0) return emptyList()
        val open = json.indexOf('[', arrayStart)
        if (open < 0) return emptyList()
        val objects = mutableListOf<String>()
        var i = open + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '{' -> {
                    val start = i
                    var depth = 1
                    var inString = false
                    var escaped = false
                    i++
                    while (i < json.length && depth > 0) {
                        val ch = json[i]
                        if (inString) {
                            when {
                                escaped -> escaped = false
                                ch == '\\' -> escaped = true
                                ch == '"' -> inString = false
                            }
                        } else {
                            when (ch) {
                                '"' -> inString = true
                                '{' -> depth++
                                '}' -> depth--
                            }
                        }
                        i++
                    }
                    objects += json.substring(start, i)
                }
                c == ']' -> break
                else -> i++
            }
        }
        return objects
    }

    /** The `"id"` field of one pack object text; null when malformed. */
    internal fun packIdOf(objectText: String): String? =
        Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(objectText)?.groupValues?.get(1)

    // ── shared ──────────────────────────────────────────────────────────

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
