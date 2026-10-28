package com.openminis.app.plugins.builtin

import android.content.Context
import com.openminis.app.R
import com.openminis.app.sandbox.SessionWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * [T-builtin-plugins] Installs a builtin plugin's payload into the guest.
 *
 * The flow is four guest-visible stages, each a pure command-builder plus
 * a thin executor:
 *
 *  1. **stage payload** — copy the APK asset zip onto the shared volume
 *     (host-side plain file copy; the guest cannot read APK assets).
 *  2. **node gate** (runtimes that need Node — `nodeMinMajor > 0`) — the
 *     OFM runtime embeds undici, which needs Node 22+
 *     (worker_threads.markAsUncloneable). The guest's apt node is 18, so
 *     when the probe comes back short we unpack a pinned arm64 Node 22
 *     tarball from npmmirror into /usr/local/lib/node-v22 and symlink the
 *     binaries into /usr/local/bin — the first path the upstream launcher's
 *     own pick_node() checks, so every later start finds it without PATH
 *     games. No apt, no lock contention with dev-suite installs.
 *     Self-contained runtimes (`nodeMinMajor == 0`, e.g. the a2a Rust
 *     binary) skip the gate entirely.
 *  3. **run upstream install.sh** — verbatim, with the payload's own
 *     `<PREFIX>_*` env contract from [BuiltinPluginCatalog.installEnv].
 *     It lays the runtime down in tool/ and writes the launcher.
 *  4. **verify** — upstream verify.sh; its first stdout line is the payload
 *     version, which we record in the store.
 *
 * Progress streams into [BuiltinPluginStore] exactly like dev-suite: one
 * store update per guest line, status transitions only on stage changes.
 */
object BuiltinPluginInstaller {

    /** Pinned Node the gate installs when the guest's node is too old. */
    internal const val NODE_VERSION = "22.14.0"

    /** npmmirror hosts the official dist tarballs and is reachable where
     *  nodesource's setup script often is not (CN networks). */
    private const val NODE_MIRROR = "https://cdn.npmmirror.com/binaries/node"

    private const val SESSION_ID = "builtin-plugin-installer"
    private const val INSTALL_TIMEOUT_MS = 20 * 60_000L

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val installMutex = Mutex()

    /** Host directory backing the guest's /var/minis/shared/builtin-plugins/<id>. */
    internal fun hostSharedDir(context: Context, id: String): File {
        val globalRoot = File(context.filesDir, SessionWorkspace.GLOBAL_DIR)
        return File(File(globalRoot, "shared"), "builtin-plugins/$id")
    }

    /**
     * Stage the payload zip: plain copy from APK assets into the shared
     * volume so the guest can see it. Versioned filename — a stale zip from
     * an older payload never satisfies a newer catalog version.
     */
    @Throws(IOException::class)
    internal fun stagePayloadZip(context: Context, d: BuiltinPluginCatalog.Descriptor): File {
        val dir = hostSharedDir(context, d.id).also { it.mkdirs() }
        val target = File(dir, "payload-${d.version}.zip")
        if (target.exists() && target.length() > 0) return target
        val tmp = File(dir, "payload-${d.version}.zip.part")
        context.assets.open(d.payloadAsset).use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot stage ${d.payloadAsset} -> $target")
        }
        return target
    }

    /** Parse a `node --version` stdout line ("v22.14.0") into its major. Pure. */
    internal fun nodeMajorOf(versionOutput: String): Int? {
        val m = Regex("""^v?(\d+)\.""").find(versionOutput.trim()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * Guest command that reports the best node major available on PATH.
     * Empty output means no node at all.
     */
    internal fun nodeProbeCommand(): String =
        "node --version 2>/dev/null || true"

    /**
     * Guest command that guarantees a Node >= [BuiltinPluginCatalog.Descriptor.nodeMinMajor]
     * on /usr/local/bin: no-op when the probe passes, otherwise fetch the
     * pinned tarball and symlink it in. Prints the final `node --version`.
     * Pure — the test asserts the exact script shape.
     */
    internal fun ensureNodeCommand(minMajor: Int): String {
        val v = NODE_VERSION
        val tar = "node-v$v-linux-arm64.tar.xz"
        return """
            m=${'$'}(node --version 2>/dev/null | sed 's/^v//' | cut -d. -f1)
            if [ -n "${'$'}m" ] && [ "${'$'}m" -ge $minMajor ] 2>/dev/null; then
              node --version; exit 0
            fi
            curl -fsSL --retry 3 "$NODE_MIRROR/v$v/$tar" -o /tmp/$tar || exit 4
            rm -rf /usr/local/lib/node-v$v
            mkdir -p /usr/local/lib/node-v$v
            tar -xJf /tmp/$tar -C /usr/local/lib/node-v$v --strip-components=1 || exit 5
            ln -sfn /usr/local/lib/node-v$v/bin/node /usr/local/bin/node
            ln -sfn /usr/local/lib/node-v$v/bin/npm /usr/local/bin/npm
            ln -sfn /usr/local/lib/node-v$v/bin/npx /usr/local/bin/npx
            rm -f /tmp/$tar
            node --version
        """.trimIndent()
    }

    /**
     * Guest command that unpacks the staged zip into payload/ and runs the
     * upstream install script with the payload's own `<PREFIX>_*` contract.
     * The env is applied with `env` so the values survive the coordinator's
     * shell wrapping. Pure.
     */
    internal fun installCommand(
        d: BuiltinPluginCatalog.Descriptor,
        stagedZipName: String,
    ): String {
        val env = BuiltinPluginCatalog.installEnv(d)
        val envPrefix = env.entries.joinToString(" ") { (k, v) -> "$k='$v'" }
        val root = BuiltinPluginCatalog.installRoot(d.id)
        // The zip's top level is manifest.json + payload/ — unzip straight
        // into the install root so payload/ lands where the env contract
        // (installRoot/payload) already points. -o: reinstall must overwrite
        // without the interactive replace prompt (no tty on that path, so
        // the prompt would read EOF and abort the unzip mid-archive).
        return """
            set -e
            rm -rf $root/payload
            unzip -o -q $root/$stagedZipName -d $root
            test -f $root/payload/scripts/${d.installScript} || { echo "payload missing scripts/${d.installScript}" >&2; exit 6; }
            env $envPrefix /bin/sh $root/payload/scripts/${d.installScript}
        """.trimIndent()
    }

    /** Guest command running the upstream verify script. Pure. */
    internal fun verifyCommand(d: BuiltinPluginCatalog.Descriptor): String {
        val env = BuiltinPluginCatalog.installEnv(d)
        val envPrefix = env.entries.joinToString(" ") { (k, v) -> "$k='$v'" }
        val root = BuiltinPluginCatalog.installRoot(d.id)
        return "env $envPrefix /bin/sh $root/payload/scripts/verify.sh"
    }

    /**
     * Fire-and-forget install the market card calls: runs in the object's
     * own scope so leaving the screen does not cancel a multi-minute
     * install (same discipline as the dev-suite assembler). The callback
     * (invoked on the scope's dispatcher) lets the card seed the provider
     * row the moment the install lands.
     */
    fun installAsync(
        context: Context,
        d: BuiltinPluginCatalog.Descriptor,
        onResult: (Boolean) -> Unit = {},
    ) {
        scope.launch { onResult(install(context, d)) }
    }

    /**
     * Full install: stage → node gate → install → verify. Streams every
     * guest line into the store; FAILED carries the stage name and the
     * output tail so the market card can self-diagnose. Returns true when
     * verify passes.
     */
    suspend fun install(context: Context, d: BuiltinPluginCatalog.Descriptor): Boolean =
        installMutex.withLock {
            _busy.value = true
            try {
                BuiltinPluginStore.update(
                    context, d.id, BuiltinPluginStore.Status.INSTALLING,
                    progressLine = context.getString(R.string.builtin_plugin_stage_payload),
                )
                val zip = withContext(Dispatchers.IO) { stagePayloadZip(context, d) }

                // Self-contained runtimes need no Node — running the gate
                // would download a Node the payload never uses.
                if (d.nodeMinMajor > 0) {
                    BuiltinPluginStore.update(
                        context, d.id, BuiltinPluginStore.Status.INSTALLING,
                        progressLine = context.getString(R.string.builtin_plugin_stage_node),
                    )
                    val nodeResult = run(ensureNodeCommand(d.nodeMinMajor)) { line ->
                        BuiltinPluginStore.update(
                            context, d.id, BuiltinPluginStore.Status.INSTALLING,
                            progressLine = line.take(120),
                        )
                    }
                    if (nodeResult.exitCode != 0) {
                        fail(context, d, "node gate", nodeResult.outputTail)
                        return false
                    }
                }

                val installResult = run(installCommand(d, zip.name)) { line ->
                    BuiltinPluginStore.update(
                        context, d.id, BuiltinPluginStore.Status.INSTALLING,
                        progressLine = line.take(120),
                    )
                }
                if (installResult.exitCode != 0) {
                    fail(context, d, "install.sh", installResult.outputTail)
                    return false
                }

                val verifyResult = run(verifyCommand(d)) { line ->
                    BuiltinPluginStore.update(
                        context, d.id, BuiltinPluginStore.Status.INSTALLING,
                        progressLine = line.take(120),
                    )
                }
                if (verifyResult.exitCode != 0) {
                    fail(context, d, "verify.sh", verifyResult.outputTail)
                    return false
                }
                // verify.sh prints the payload version on stdout line 1.
                val version = verifyResult.outputLines.firstOrNull { it.isNotBlank() }?.trim()
                    ?: d.version
                BuiltinPluginStore.update(
                    context, d.id, BuiltinPluginStore.Status.INSTALLED,
                    version = version,
                    progressLine = context.getString(R.string.builtin_plugin_ready),
                )
                true
            } finally {
                _busy.value = false
            }
        }

    private suspend fun fail(
        context: Context,
        d: BuiltinPluginCatalog.Descriptor,
        stage: String,
        tail: String,
    ) {
        BuiltinPluginStore.update(
            context, d.id, BuiltinPluginStore.Status.FAILED,
            diagnosis = "$stage: ${tail.take(200)}",
        )
    }

    private data class Result(val exitCode: Int, val outputLines: List<String>, val outputTail: String)

    private suspend fun run(command: String, onLine: (String) -> Unit): Result {
        val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            sessionId = SESSION_ID,
            command = command,
            timeout = INSTALL_TIMEOUT_MS,
            lineCallback = onLine,
        )
        val lines = result.output.lines().filter { it.isNotBlank() }
        return Result(result.exitCode, lines, lines.takeLast(8).joinToString(" / "))
    }
}
