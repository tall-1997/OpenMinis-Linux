package com.openminis.app.vscreen.tier

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.HostSuFallback
import java.io.File
import java.security.MessageDigest

/**
 * [T-android-vscreen-tier] Deploys the root-tier host jar (built by the
 * `buildVdHostJar` Gradle task into app assets) to /data/local/tmp through
 * the su lane, with a SHA-256 gate so an unchanged host is never re-copied.
 *
 * The app cannot touch /data/local/tmp directly (shell-owned directory);
 * the jar first lands in the app cache dir via a plain asset copy, then
 * `su -c cp` moves it into place — same transport shape as the sandbox
 * offload chain, one privilege level up.
 */
object VdHostDeployer {

    private const val TAG = "VdHostDeployer"
    private const val ASSET_NAME = "vdhost/minis_vd_host.jar"
    private const val CACHE_DIR = "vdhost"

    /**
     * Ensures /data/local/tmp/minis_vd_host.jar matches the bundled asset.
     * Returns null on success, or the failure reason for the orchestrator.
     */
    fun ensureDeployed(context: Context, su: HostSuRunner): String? {
        val cacheJar = extractAsset(context) ?: return "host asset $ASSET_NAME missing from the APK"
        val localSha = sha256(cacheJar) ?: return "cannot hash the extracted host jar"
        // The assembled compound command travels as ONE argv slot: the su
        // lane joins and quotes it into `su -c '<cmd>'`, where the host
        // shell interprets the && chain verbatim.
        val deploy = su.run(arrayOf(VdHostProtocol.deployCommand(cacheJar.absolutePath)), 10_000L)
        if (deploy.exitCode != 0) {
            return "host deploy failed (exit ${deploy.exitCode}): ${deploy.combined.take(200)}"
        }
        // sha256sum output is "<hash>  <path>"; compare case-insensitively (some
        // toybox builds print uppercase).
        val remoteSha = deploy.combined.trim().lineSequence()
            .firstOrNull { it.isNotBlank() }?.split(Regex("\\s+"))?.firstOrNull()
        if (!remoteSha.equals(localSha, ignoreCase = true)) {
            return "host deploy sha256 mismatch (local=$localSha remote=$remoteSha)"
        }
        return null
    }

    /** Extracts the bundled asset into the app cache dir (plain file copy). */
    private fun extractAsset(context: Context): File? = runCatching {
        val dir = File(context.applicationContext.cacheDir, CACHE_DIR).apply { mkdirs() }
        val out = File(dir, "minis_vd_host.jar")
        context.assets.open(ASSET_NAME).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out
    }.getOrNull()

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.onFailure {
        AppLogger.warning(TAG, "sha256 failed: ${it.message}")
    }.getOrNull()
}
