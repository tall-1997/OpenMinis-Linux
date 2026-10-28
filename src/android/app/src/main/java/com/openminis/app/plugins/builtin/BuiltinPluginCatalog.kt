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
        /** Minimum Node major the runtime needs (undici wants 22). */
        val nodeMinMajor: Int,
        /** Loopback port the installed service serves. */
        val servicePort: Int,
        /** Health/models path on that port, leading slash. */
        val serviceHealthPath: String,
        /** The BuiltinFreeLanes lane this plugin backs, e.g. "ofm". */
        val laneId: String,
    )

    /**
     * The local Our-Free-Model gateway: an OpenAI-compatible loopback proxy
     * over upstream free channels. Port 18900 and the OFM_* env names are
     * the upstream payload's contract, not ours to choose.
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
    )

    val ALL: List<Descriptor> = listOf(OFM)

    fun byId(id: String): Descriptor? = ALL.firstOrNull { it.id == id }

    fun installRoot(id: String): String = "/var/minis/shared/builtin-plugins/$id"

    fun payloadDir(id: String): String = "${installRoot(id)}/payload"

    fun toolDir(id: String): String = "${installRoot(id)}/tool"

    fun dataDir(id: String): String = "${installRoot(id)}/data"

    /**
     * Env for the install script: the OFM_* contract (upstream names,
     * upstream semantics — payload dir, tool dir, data dir) plus PATH so
     * node/npm resolve inside the sandbox session.
     */
    fun installEnv(d: Descriptor): Map<String, String> = mapOf(
        "OFM_PLUGIN_PAYLOAD" to payloadDir(d.id),
        "OFM_TOOL_DIR" to toolDir(d.id),
        "OFM_TOOL_DATA" to dataDir(d.id),
        "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    )

    /** Loopback health URL — the service binds 127.0.0.1 only. */
    fun healthUrl(d: Descriptor): String =
        "http://127.0.0.1:${d.servicePort}${d.serviceHealthPath}"
}
