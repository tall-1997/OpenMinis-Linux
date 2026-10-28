package com.openminis.app.plugins.builtin

import com.openminis.app.R

/**
 * Catalog of builtin plugins — runtime payloads the app ships in its assets
 * and deploys on demand. Two kinds share one descriptor shape:
 *
 *  - **Guest** plugins unpack into the PRoot sandbox and run there
 *    (Node runtimes, shell installers). The on-disk layout under
 *    [installRoot] is:
 *    ```
 *    /var/minis/shared/builtin-plugins/<id>/
 *      payload/   unpacked asset zip, upstream scripts untouched
 *      tool/      the runtime the install script lays down (node_modules, bins)
 *      data/      service state: logs, generated keys, anything the gateway writes
 *    ```
 *    `shared/` deliberately: host-visible, inspectable, survives an app-data
 *    clear, and the same place dev-suite offline payloads live.
 *
 *  - **Native** plugins ship a bionic binary inside the APK
 *    ([Descriptor.nativeBinary] in nativeLibraryDir) and run as a host app
 *    process — no guest, no Node, no PRoot. Their asset zip is the web-panel
 *    bundle, deployed to the app-private home [nativeHomeDir]:
 *    ```
 *    <filesDir>/builtin-plugins/<id>/
 *      ui/        panel bundle (index.html + assets)
 *      data/      gateway state: SQLite, logs, generated keys
 *    ```
 *    Private, not shared: the native process runs as the app's own uid, so
 *    it needs no shared volume, and app-private storage keeps gateway
 *    credentials out of every other sandbox session's reach.
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
        /**
         * Zip inside APK assets. For guest plugins this is the runtime
         * payload the installer unpacks into the guest; for native plugins
         * it is the web-panel bundle deployed to the app's private dir.
         */
        val payloadAsset: String,
        /** Install entry inside the payload, relative to payload/. Guest only. */
        val installScript: String?,
        /**
         * Minimum Node major the runtime needs (undici wants 22), or 0 when
         * the runtime is self-contained and needs no Node at all — the
         * installer then skips the node gate entirely. Guest only.
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
         * names its own variables (`OFM_TOOL_DIR`, `A2A_TOOL_DIR`), so the
         * prefix travels with the descriptor instead of living in the
         * installer. Guest only.
         */
        val envPrefix: String?,
        /**
         * Executable the install script lays down at tool/bin/ for the host
         * to launch (foreground; the host backgrounds it with nohup).
         * Guest only.
         */
        val launcher: String?,
        /** Web panel path on the service port, or null when the plugin serves no panel. */
        val panelPath: String?,
        /**
         * Name of the bionic binary in the APK's nativeLibraryDir when this
         * plugin runs as a host process (e.g. `liba2a.so` — Android package
         * extraction only unpacks `lib*.so`, hence the extension), or null
         * when the plugin runs inside the guest. Native plugins skip the
         * guest installer entirely: the binary ships in the APK and the
         * "install" is a host-side panel-bundle deployment.
         */
        val nativeBinary: String? = null,
        /**
         * Prefix of the upstream binary's own runtime env contract for a
         * native plugin (e.g. `AGENT2API` → `AGENT2API_HOST`,
         * `AGENT2API_PROXY_PORT`, …). Null for guest plugins.
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
    private val OFM = Descriptor(
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
        panelPath = null,
    )

    /**
     * The local Agent2API gateway (github.com/aimod-cc/Agent2API, MIT): a
     * Rust binary serving an OpenAI-compatible proxy plus a web panel on
     * the same loopback port. Runs as a **host process**: CI cross-compiles
     * the upstream server for Android bionic (aarch64-linux-android) and the
     * APK ships it as `liba2a.so` in nativeLibraryDir — no guest, no Node,
     * no PRoot overhead. The payload asset is the panel bundle; "install"
     * deploys it to the app's private dir. Accounts are added in the panel;
     * the models listing is empty until one is.
     */
    private val A2A = Descriptor(
        id = "a2a",
        nameRes = R.string.builtin_plugin_a2a_name,
        summaryRes = R.string.builtin_plugin_a2a_summary,
        version = "3.0.8",
        payloadAsset = "builtin_plugins/a2a-ui.zip",
        installScript = null,
        nodeMinMajor = 0,
        servicePort = 18901,
        serviceHealthPath = "/health",
        laneId = "a2a",
        envPrefix = null,
        launcher = null,
        panelPath = "/",
        nativeBinary = "liba2a.so",
        nativeEnvPrefix = "AGENT2API",
    )

    val ALL: List<Descriptor> = listOf(OFM, A2A)

    fun byId(id: String): Descriptor? = ALL.firstOrNull { it.id == id }

    fun installRoot(id: String): String = "/var/minis/shared/builtin-plugins/$id"

    fun payloadDir(id: String): String = "${installRoot(id)}/payload"

    fun toolDir(id: String): String = "${installRoot(id)}/tool"

    fun dataDir(id: String): String = "${installRoot(id)}/data"

    /**
     * Env for the install script: the payload's own `<PREFIX>_*` contract
     * (payload dir, tool dir, data dir — see [Descriptor.envPrefix]) plus
     * PATH so node/npm resolve inside the sandbox session. Guest plugins
     * only; native plugins never run a guest install script.
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

    /**
     * Host-side home for a native plugin's runtime state: the app's private
     * `builtin-plugins/<id>/` carrying `ui/` (deployed panel bundle) and
     * `data/` (gateway state: SQLite, logs, generated keys). Private, not
     * shared: the native process runs as the app's own uid, so it needs no
     * shared volume, and app-private storage keeps gateway credentials out
     * of every other sandbox session's reach.
     */
    fun nativeHomeDir(filesDir: String, id: String): String =
        "$filesDir/builtin-plugins/$id"

    /**
     * Host-process env for a native plugin: the upstream binary's own
     * `<NATIVE_ENV_PREFIX>_*` runtime contract, pointed at the private home.
     * `ALLOW_NO_KEY` mirrors the guest launcher's stance — loopback only,
     * the app itself is the only client. HOME/TMPDIR give the bionic process
     * writable scratch inside the private dir (bionic has no /tmp, and a
     * null HOME breaks SQLite temp files).
     */
    fun nativeEnv(d: Descriptor, filesDir: String): Map<String, String> {
        val prefix = requireNotNull(d.nativeEnvPrefix) { "native env prefix for ${d.id}" }
        val home = nativeHomeDir(filesDir, d.id)
        return mapOf(
            "${prefix}_HOST" to "127.0.0.1",
            "${prefix}_PROXY_PORT" to d.servicePort.toString(),
            "${prefix}_PROXY_HOME" to "$home/data",
            "${prefix}_UI_DIR" to "$home/ui",
            "${prefix}_ALLOW_NO_KEY" to "1",
            "HOME" to "$home/data",
            "TMPDIR" to "$home/data/tmp",
        )
    }

    /** Loopback health URL — the service binds 127.0.0.1 only. */
    fun healthUrl(d: Descriptor): String =
        "http://127.0.0.1:${d.servicePort}${d.serviceHealthPath}"

    /** Loopback panel URL, or null when the plugin serves no panel. */
    fun panelUrl(d: Descriptor): String? =
        d.panelPath?.let { "http://127.0.0.1:${d.servicePort}$it" }
}
