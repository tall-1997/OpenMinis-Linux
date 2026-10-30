package com.openminis.app.plugins.builtin

import com.openminis.app.R

/**
 * Catalog of builtin plugins — runtime payloads the app ships in its assets
 * and deploys on demand. Guest plugins unpack into the PRoot sandbox and run
 * there (Node runtimes, shell installers). The on-disk layout under
 * [installRoot] is:
 * ```
 * /var/minis/shared/builtin-plugins/<id>/
 *   payload/   unpacked asset zip, upstream scripts untouched
 *   tool/      the runtime the install script lays down (node_modules, bins)
 *   data/      service state: logs, generated keys, anything the gateway writes
 * ```
 * `shared/` deliberately: host-visible, inspectable, survives an app-data
 * clear, and the same place dev-suite offline payloads live.
 *
 * (Native plugins used to live here too — the a2a gateway shipped a bionic
 * binary and ran as a host app process. It has since left the catalog for
 * its own package, `com.openminis.app.a2a` ([com.openminis.app.a2a.A2aGateway]):
 * the engine ([com.openminis.app.a2a.A2aInstaller],
 * [com.openminis.app.a2a.A2aGatewayProcess]) moved with it and the lane
 * layer stayed. The descriptor keeps the guest/native discrimination
 * ([Descriptor.isNative] and the two native fields below it) so a future
 * native plugin could return through the same door, and the guest install
 * pipeline keeps refusing one; no current descriptor is native.)
 *
 * A descriptor is pure data plus the layout contract every builtin plugin
 * shares, so adding a plugin is adding one entry here (plus its asset zip
 * and its lane). Nothing in this file touches Context or the disk: path
 * and env derivation are pure functions the installers and the tests share.
 */
object BuiltinPluginCatalog {

    data class Descriptor(
        /** Filesystem-safe, stable forever — it keys the install dir and the store. */
        val id: String,
        val nameRes: Int,
        val summaryRes: Int,
        /** Upstream payload version, shown in the market card. */
        val version: String,
        /** Zip inside APK assets: the runtime payload the installer unpacks into the guest. */
        val payloadAsset: String,
        /** Install entry inside the payload, relative to payload/. */
        val installScript: String?,
        /**
         * Minimum Node major the runtime needs (undici wants 22), or 0 when
         * the runtime is self-contained and needs no Node at all — the
         * installer then skips the node gate entirely.
         */
        val nodeMinMajor: Int,
        /** Loopback port the installed service serves. */
        val servicePort: Int,
        /** Health/models path on that port, leading slash. */
        val serviceHealthPath: String,
        /** The BuiltinFreeLanes lane this plugin backs, e.g. "ofm". */
        val laneId: String,
        /**
         * Prefix of the payload's install-script env contract — each payload
         * names its own variables (`OFM_TOOL_DIR`), so the prefix travels
         * with the descriptor instead of living in the installer.
         */
        val envPrefix: String?,
        /**
         * Executable the install script lays down at tool/bin/ for the host
         * to launch (foreground; the host backgrounds it with nohup).
         */
        val launcher: String?,
        /**
         * Name of a bionic binary in the APK's nativeLibraryDir for a plugin
         * that runs as a host process (Android package extraction only
         * unpacks `lib*.so`, hence the extension), or null when the plugin
         * runs inside the guest. No current descriptor is native — the a2a
         * gateway was the one, and it left the catalog for
         * `com.openminis.app.a2a`. The field stays so a future native plugin
         * could return through the same door and the guest pipeline's
         * refusal of one keeps compiling.
         */
        val nativeBinary: String? = null,
        /**
         * Prefix of a native plugin's upstream binary env contract. Null for
         * guest plugins — same dormant-for-now note as [nativeBinary].
         */
        val nativeEnvPrefix: String? = null,
    ) {
        /** True when this plugin runs as a host process from the APK. */
        val isNative: Boolean get() = nativeBinary != null
    }

    /**
     * The local Our-Free-Model gateway: an OpenAI-compatible loopback proxy
     * over upstream free channels. Port 18900 and the OFM_* env names are
     * the payload's install contract.
     */
    val OFM = Descriptor(
        id = "ofm",
        nameRes = R.string.builtin_plugin_ofm_name,
        summaryRes = R.string.builtin_plugin_ofm_summary,
        version = "2.3.0",
        payloadAsset = "builtin_plugins/ofm-runtime.zip",
        installScript = "install.sh",
        nodeMinMajor = 22,
        servicePort = 18900,
        serviceHealthPath = "/v1/models",
        laneId = "ofm",
        envPrefix = "OFM",
        launcher = "ofm-gateway",
    )

    val ALL: List<Descriptor> = listOf(OFM)

    fun byId(id: String): Descriptor? = ALL.firstOrNull { it.id == id }

    fun installRoot(id: String): String = "/var/minis/shared/builtin-plugins/$id"

    fun payloadDir(id: String): String = "${installRoot(id)}/payload"

    fun toolDir(id: String): String = "${installRoot(id)}/tool"

    fun dataDir(id: String): String = "${installRoot(id)}/data"

    /**
     * Env for the install script: the payload's own `<PREFIX>_*` contract
     * (payload dir, tool dir, data dir — see [Descriptor.envPrefix]) plus
     * PATH so node/npm resolve inside the sandbox session.
     */
    fun installEnv(d: Descriptor): Map<String, String> {
        val prefix = d.envPrefix ?: return emptyMap()
        return mapOf(
            "${prefix}_PLUGIN_PAYLOAD" to payloadDir(d.id),
            "${prefix}_TOOL_DIR" to toolDir(d.id),
            "${prefix}_TOOL_DATA" to dataDir(d.id),
            "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        )
    }

    /** Loopback health URL — the service binds 127.0.0.1 only. */
    fun healthUrl(d: Descriptor): String =
        "http://127.0.0.1:${d.servicePort}${d.serviceHealthPath}"
}
