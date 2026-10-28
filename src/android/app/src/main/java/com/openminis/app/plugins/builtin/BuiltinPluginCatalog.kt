package com.openminis.app.plugins.builtin

import com.openminis.app.R

/**
 * Catalog of builtin plugins — self-contained runtime payloads the app ships
 * in its assets and installs into the guest on demand.
 *
 * A descriptor is pure data plus the layout contract every builtin plugin
 * shares, so adding a second plugin is adding one entry here (plus its asset
 * zip and its lane). Nothing in this file touches Context or the disk: path
 * and env derivation are pure functions the installer and the tests share.
 *
 * The on-disk layout under [installRoot] is:
 * ```
 * /var/minis/shared/builtin-plugins/<id>/
 *   payload/   unpacked asset zip, upstream scripts untouched
 *   tool/      the runtime the install script lays down (node_modules, bins)
 *   data/      service state: logs, generated keys, anything the gateway writes
 * ```
 * `shared/` deliberately: host-visible, inspectable, survives an app-data
 * clear, and the same place dev-suite offline payloads live.
 */
object BuiltinPluginCatalog {

    data class Descriptor(
        /** Filesystem-safe, stable forever — it keys the install dir and the store. */
        val id: String,
        val nameRes: Int,
        val summaryRes: Int,
        /** Upstream payload version, shown in the market card. */
        val version: String,
        /** Zip inside APK assets, e.g. `builtin_plugins/ofm-runtime.zip`. */
        val payloadAsset: String,
        /** Install entry inside the payload, relative to payload/. */
        val installScript: String,
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
         * names its own variables (`OFM_TOOL_DIR`, `A2A_TOOL_DIR`), so the
         * prefix travels with the descriptor instead of living in the
         * installer.
         */
        val envPrefix: String,
        /**
         * Executable the install script lays down at tool/bin/ for the host
         * to launch (foreground; the host backgrounds it with nohup).
         */
        val launcher: String,
        /** Web panel path on the service port, or null when the plugin serves no panel. */
        val panelPath: String?,
    )

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
     * self-contained Rust binary serving an OpenAI-compatible proxy plus a
     * web panel on the same loopback port. No Node anywhere — the binary is
     * the runtime — and it parses no CLI args (even `--help` boots), so the
     * payload's launcher intercepts version/help itself. Accounts are added
     * in the panel; the models listing is empty until one is.
     */
    private val A2A = Descriptor(
        id = "a2a",
        nameRes = R.string.builtin_plugin_a2a_name,
        summaryRes = R.string.builtin_plugin_a2a_summary,
        version = "3.0.8",
        payloadAsset = "builtin_plugins/a2a-runtime.zip",
        installScript = "install.sh",
        nodeMinMajor = 0,
        servicePort = 18901,
        serviceHealthPath = "/health",
        laneId = "a2a",
        envPrefix = "A2A",
        launcher = "a2a-gateway",
        panelPath = "/",
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
     * PATH so node/npm resolve inside the sandbox session.
     */
    fun installEnv(d: Descriptor): Map<String, String> = mapOf(
        "${d.envPrefix}_PLUGIN_PAYLOAD" to payloadDir(d.id),
        "${d.envPrefix}_TOOL_DIR" to toolDir(d.id),
        "${d.envPrefix}_TOOL_DATA" to dataDir(d.id),
        "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    )

    /** Loopback health URL — the service binds 127.0.0.1 only. */
    fun healthUrl(d: Descriptor): String =
        "http://127.0.0.1:${d.servicePort}${d.serviceHealthPath}"

    /** Loopback panel URL, or null when the plugin serves no panel. */
    fun panelUrl(d: Descriptor): String? =
        d.panelPath?.let { "http://127.0.0.1:${d.servicePort}$it" }
}
