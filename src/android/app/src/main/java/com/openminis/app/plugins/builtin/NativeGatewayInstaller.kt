package com.openminis.app.plugins.builtin

import android.content.Context
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * [T-builtin-plugins] Deploys a **native** builtin plugin on the host.
 *
 * Native plugins ship their runtime binary inside the APK
 * ([BuiltinPluginCatalog.Descriptor.nativeBinary] in nativeLibraryDir —
 * Android package extraction only unpacks `lib*.so`, hence the extension),
 * so "install" is not a guest operation at all: it is a host-side deployment
 * of the panel bundle from APK assets into the app's private home
 * ([BuiltinPluginCatalog.nativeHomeDir]), plus the state dirs the binary
 * expects. No sandbox session, no Node, no PRoot — which is the point: the
 * guest path was a compatibility shim for a binary that runs perfectly well
 * as an app process.
 *
 * The deployment is atomic in the same spirit as the guest install.sh: the
 * bundle unzips into a staging dir and swaps in with a rename, so a crash
 * mid-deploy can never leave a half panel next to a live gateway.
 *
 * Progress streams into [BuiltinPluginStore] exactly like the guest
 * installer, so the market card's state machine is unchanged.
 */
object NativeGatewayInstaller {

    /**
     * Deploys the panel bundle for [d]. Returns true when the home layout is
     * complete: `ui/index.html` present, `data/` and `data/tmp/` writable,
     * and the native binary actually present in the APK's nativeLibraryDir
     * (a missing binary means the APK was built without the native-deps
     * artifact — fail loudly, not at first launch).
     */
    suspend fun install(context: Context, d: BuiltinPluginCatalog.Descriptor): Boolean =
        withContext(Dispatchers.IO) {
            val binary = requireNotNull(d.nativeBinary) { "${d.id} is not native" }
            BuiltinPluginStore.update(
                context, d.id, BuiltinPluginStore.Status.INSTALLING,
                progressLine = context.getString(R.string.builtin_plugin_stage_payload),
            )
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, binary)
                if (!bin.isFile || !bin.canExecute()) {
                    fail(context, d, "native binary missing from APK: $binary")
                    return@withContext false
                }

                val home = File(BuiltinPluginCatalog.nativeHomeDir(context.filesDir.path, d.id))
                val ui = File(home, "ui")
                val staging = File(home, ".staging")
                staging.deleteRecursively()
                staging.mkdirs()

                context.assets.open(d.payloadAsset).use { zip ->
                    ZipInputStream(zip.buffered()).use { stream ->
                        var entry = stream.nextEntry
                        while (entry != null) {
                            val target = deployTarget(staging, entry.name)
                                ?: return@use // zip-slip guard: skip entries outside staging
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                stream.copyTo(target.outputStream().buffered())
                            }
                            entry = stream.nextEntry
                        }
                    }
                }
                val index = File(staging, "index.html").takeIf { it.isFile }
                    ?: File(staging, "ui/index.html").takeIf { it.isFile }
                if (index == null) {
                    staging.deleteRecursively()
                    fail(context, d, "panel bundle missing index.html")
                    return@withContext false
                }
                // Normalize: the bundle may carry its own top-level ui/ dir.
                val stagedRoot = if (index.parentFile == staging) staging else index.parentFile!!
                ui.deleteRecursively()
                if (!stagedRoot.renameTo(ui)) {
                    staging.deleteRecursively()
                    fail(context, d, "panel deploy failed")
                    return@withContext false
                }
                home.resolve(".staging").deleteRecursively()

                val data = File(home, "data")
                data.mkdirs()
                File(data, "tmp").mkdirs()

                BuiltinPluginStore.update(
                    context, d.id, BuiltinPluginStore.Status.INSTALLED,
                    version = d.version,
                    progressLine = context.getString(R.string.builtin_plugin_ready),
                )
                true
            } catch (t: Throwable) {
                fail(context, d, t.message ?: t.javaClass.simpleName)
                false
            }
        }

    /**
     * Fire-and-forget deploy the market card calls for native plugins:
     * runs in the object's own scope so leaving the screen does not
     * cancel it (same discipline as the guest installer). The callback
     * lets the card seed the provider row the moment the deploy lands.
     */
    fun installAsync(
        context: Context,
        d: BuiltinPluginCatalog.Descriptor,
        onResult: (Boolean) -> Unit = {},
    ) {
        require(d.isNative) { "${d.id} is not native" }
        scope.launch { onResult(install(context, d)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fail(context: Context, d: BuiltinPluginCatalog.Descriptor, why: String) {
        BuiltinPluginStore.update(
            context, d.id, BuiltinPluginStore.Status.FAILED,
            diagnosis = why.take(200),
        )
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
