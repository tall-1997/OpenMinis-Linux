package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.UpdateVersionLogic
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persists the update flow across Activity recreate / process death.
 *
 * Why this exists: the original update flow held every piece of state in
 * Composable `remember{}` slots. When the user tapped "Open Settings" to grant
 * "install unknown apps" permission the system pushed Minis to the background;
 * on return the Activity often recreated, the slots were reset, and the UI
 * either silently asked the user to download the APK again or stranded them on
 * a dialog whose only action button had gone dead.
 *
 * Two records live here:
 *
 *  - **[PendingUpdate]** — an APK that has been fully downloaded but not yet
 *    installed. Written by [UpdateDownloadManager] on completion.
 *  - **[PendingIntent]** — a download the user explicitly asked for that we
 *    could not start because install permission was still missing. Written
 *    when the UI hands off to system Settings; consumed on return.
 *
 * Both are needed. Without [PendingUpdate] a process kill between "download
 * finished" and "user grants permission" loses the 98 MB APK. Without
 * [PendingIntent] a process kill *while the user is in Settings* loses the
 * fact that they ever asked for the download, so nothing resumes on return.
 *
 * Storage: SharedPreferences keys holding small JSON blobs. We deliberately
 * avoid DataStore here — this object is touched at most a couple times per
 * update flow, blocking access is fine, and SharedPreferences is already
 * initialised elsewhere.
 *
 * Freshness: records older than [MAX_AGE_MS] (24 h) are discarded on read so
 * a stale APK can't auto-install on cold start a week later after the GitHub
 * release was re-rolled.
 *
 * Integrity: we compute and store sha256 if [setPending] is given the file
 * bytes; if absent, [verify] falls back to (size == expectedSize).
 */
object PendingUpdateStore {

    private const val TAG = "PendingUpdateStore"
    private const val PREFS = "pending_update"
    private const val KEY = "pending"
    private const val KEY_INTENT = "pending_intent"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    data class PendingUpdate(
        val targetVersionName: String,
        val apkPath: String,
        val apkSize: Long,
        val sha256: String?,
        val downloadedAtMs: Long,
        /**
         * When the system installer intent was last fired for this APK, or 0
         * when it has never been launched. Used to decide whether a resume
         * should auto-fire the installer again (the one automatic launch,
         * right after the user grants permission) or offer an explicit
         * "install" affordance instead of re-prompting on every resume.
         */
        val installLaunchedAtMs: Long = 0L,
    )

    /**
     * A download the user asked for that is waiting on install permission.
     * Written before handing off to system Settings, consumed on return.
     */
    data class PendingIntent(
        val targetVersionName: String,
        val apkUrl: String,
        val apkSize: Long,
        val requestedAtMs: Long,
    )

    private var prefs: SharedPreferences? = null

    /** Idempotent. Safe to call from MinisApp.onCreate. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    fun setPending(context: Context, pending: PendingUpdate) {
        val json = JSONObject().apply {
            put("targetVersionName", pending.targetVersionName)
            put("apkPath", pending.apkPath)
            put("apkSize", pending.apkSize)
            if (pending.sha256 != null) put("sha256", pending.sha256) else put("sha256", JSONObject.NULL)
            put("downloadedAtMs", pending.downloadedAtMs)
            put("installLaunchedAtMs", pending.installLaunchedAtMs)
        }
        requirePrefs(context).edit().putString(KEY, json.toString()).apply()
        AppLogger.info(
            TAG,
            "setPending version=${pending.targetVersionName} size=${pending.apkSize} sha256=${pending.sha256 != null}",
        )
    }

    /**
     * Record that the system installer intent was fired for the pending APK.
     *
     * Deliberately does NOT drop the record. "The installer was launched" is
     * not "the APK was installed": the user can back out of the system
     * installer, and MIUI/Xiaomi builds can refuse the intent outright. When
     * that happened the old code had already cleared the record, so the
     * downloaded APK became an orphan that nothing referenced — the next
     * "Check for Updates" had to re-fetch all 98 MB. The record now survives
     * until [UpdateChecker.resumableInstall] observes the running build
     * has caught up with [PendingUpdate.targetVersionName].
     */
    fun markInstallLaunched(context: Context) {
        val p = requirePrefs(context)
        val raw = p.getString(KEY, null) ?: return
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        obj.put("installLaunchedAtMs", System.currentTimeMillis())
        p.edit().putString(KEY, obj.toString()).apply()
        AppLogger.info(TAG, "markInstallLaunched version=${obj.optString("targetVersionName")}")
    }

    fun setPendingIntent(context: Context, intent: PendingIntent) {
        val json = JSONObject().apply {
            put("targetVersionName", intent.targetVersionName)
            put("apkUrl", intent.apkUrl)
            put("apkSize", intent.apkSize)
            put("requestedAtMs", intent.requestedAtMs)
        }
        requirePrefs(context).edit().putString(KEY_INTENT, json.toString()).apply()
        AppLogger.info(TAG, "setPendingIntent version=${intent.targetVersionName} size=${intent.apkSize}")
    }

    /** Returns the outstanding download request, or null when none / expired. */
    fun getPendingIntent(context: Context): PendingIntent? {
        val p = requirePrefs(context)
        val raw = p.getString(KEY_INTENT, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            p.edit().remove(KEY_INTENT).apply()
            return null
        }
        val intent = PendingIntent(
            targetVersionName = obj.optString("targetVersionName"),
            apkUrl = obj.optString("apkUrl"),
            apkSize = obj.optLong("apkSize"),
            requestedAtMs = obj.optLong("requestedAtMs"),
        )
        if (intent.apkUrl.isBlank() || System.currentTimeMillis() - intent.requestedAtMs > MAX_AGE_MS) {
            AppLogger.info(TAG, "pending intent expired/blank; clearing")
            clearPendingIntent(context)
            return null
        }
        if (isStaleForRunningBuild(context, intent.targetVersionName)) {
            AppLogger.info(TAG, "pending intent ${intent.targetVersionName} superseded; clearing")
            clearPendingIntent(context)
            return null
        }
        return intent
    }

    fun clearPendingIntent(context: Context) {
        requirePrefs(context).edit().remove(KEY_INTENT).apply()
        AppLogger.info(TAG, "clearPendingIntent")
    }

    /** True when either half of the flow has something persisted to resume. */
    fun hasPendingWork(context: Context): Boolean =
        getPending(context) != null || getPendingIntent(context) != null

    /**
     * Returns the persisted pending update, or null when:
     *  - nothing stored
     *  - JSON malformed (treated as gone, cleared)
     *  - older than [MAX_AGE_MS] (cleared)
     *  - target version no longer newer than the running build
     */
    fun getPending(context: Context): PendingUpdate? {
        val p = requirePrefs(context)
        val raw = p.getString(KEY, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            AppLogger.warning(TAG, "stored JSON malformed, discarding")
            p.edit().remove(KEY).apply()
            return null
        }
        val pending = PendingUpdate(
            targetVersionName = obj.optString("targetVersionName"),
            apkPath = obj.optString("apkPath"),
            apkSize = obj.optLong("apkSize"),
            sha256 = obj.optString("sha256", "").takeIf { it.isNotEmpty() && it != "null" },
            downloadedAtMs = obj.optLong("downloadedAtMs"),
            installLaunchedAtMs = obj.optLong("installLaunchedAtMs"),
        )
        val age = System.currentTimeMillis() - pending.downloadedAtMs
        if (age > MAX_AGE_MS) {
            AppLogger.info(TAG, "pending update expired age=${age}ms; clearing")
            clearPending(context)
            return null
        }
        if (isStaleForRunningBuild(context, pending.targetVersionName)) {
            AppLogger.info(
                TAG,
                "pending update ${pending.targetVersionName} superseded by running build; clearing",
            )
            clearPending(context)
            return null
        }
        return pending
    }

    /**
     * True when [targetVersionName] would NOT upgrade the running build.
     * Guards against the "already latest but still asked to install" bug:
     * the record survives `markInstallLaunched` by design, so the only
     * reliable "install succeeded" signal is the running build itself.
     */
    internal fun isStaleForRunningBuild(context: Context, targetVersionName: String): Boolean {
        if (targetVersionName.isBlank()) return false
        val installed = try {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: com.openminis.app.BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            com.openminis.app.BuildConfig.VERSION_NAME
        }
        return UpdateVersionLogic.compareVersions(
            targetVersionName,
            UpdateVersionLogic.normalizeTag(installed),
        ) <= 0
    }

    fun clearPending(context: Context) {
        requirePrefs(context).edit().remove(KEY).apply()
        AppLogger.info(TAG, "clearPending")
    }

    /**
     * Strict integrity check used before firing the install intent on resume:
     *  - file exists
     *  - length matches recorded size
     *  - if sha256 was recorded, recomputed hash matches
     *
     * Returns the File when valid, null when corrupt/missing (caller should
     * clear the pending record and re-download).
     */
    fun verify(pending: PendingUpdate): File? {
        val f = File(pending.apkPath)
        if (!f.exists()) {
            AppLogger.warning(TAG, "verify: file missing ${pending.apkPath}")
            return null
        }
        if (f.length() != pending.apkSize) {
            AppLogger.warning(TAG, "verify: size mismatch expected=${pending.apkSize} actual=${f.length()}")
            return null
        }
        if (pending.sha256 != null) {
            val actual = runCatching { sha256(f) }.getOrNull()
            if (actual == null || !actual.equals(pending.sha256, ignoreCase = true)) {
                AppLogger.warning(TAG, "verify: sha256 mismatch expected=${pending.sha256} actual=$actual")
                return null
            }
        }
        return f
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
