 package com.openminis.app.data

 import com.openminis.app.agent.HarnessConfig
 import org.json.JSONObject
 import java.io.File

 /**
  * [T-harness-config-store] Per-MODEL context-budget overrides.
  *
  * WHY per model and not per session: the acceptance is "a 32K-window model
  * and a 200K-window model each use their own numbers", which is a property of
  * the MODEL. Keying by model entry id also avoids the trap the deleted
  * per-session version fell into: it kept the value in one process-wide
  * `@Volatile`, so any two chats open at once shared (and overwrote) each
  * other's numbers.
  *
  * Resolution is EXPLICIT: callers pass the resolved [HarnessConfig] down into
  * the budget/aging helpers instead of reading a global. A global read is what
  * made the previous attempt unreachable AND unsafe: nothing wrote it, so the
  * feature never ran, while the field would have leaked across sessions the
  * moment anything did.
  *
  * Storage: `<filesDir>/harness-config/<entry-id>.json`, atomic replace, corrupt
  * or invalid files fall back to the shipped defaults and never break a turn.
  */
 internal object HarnessConfigStore {

    internal const val DIR = "harness-config"

    /** [T-harness-config-store] Ladder bounds for the UI stepper. */
    internal const val MIN_TAIL_BUDGET = 8_000
    internal const val MAX_TAIL_BUDGET = 60_000
    internal const val TAIL_BUDGET_STEP = 2_000

    private const val CACHE_MAX = 32

    private class Entry(val modifiedAtMs: Long, val config: HarnessConfig)

    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(16,0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Entry>,
            ): Boolean = size > CACHE_MAX
        },
    )

    /** Filesystem-safe key for one model entry. */
    internal fun keyOf(entryId: String?): String? =
        entryId?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")

    internal fun file(filesDir: File, entryId: String): File =
        File(File(filesDir, DIR), keyOf(entryId) + ".json")

    /** The stored override for this model, or the shipped defaults. */
    fun forModel(filesDir: File?, entryId: String?): HarnessConfig {
        val key = keyOf(entryId) ?: return HarnessConfig.DEFAULT
        if (filesDir == null) return HarnessConfig.DEFAULT
        val target = file(filesDir, key)
        if (!target.isFile) return HarnessConfig.DEFAULT
        val modified = runCatching { target.lastModified() }.getOrDefault(0L)
        synchronized(cache) {
            cache[key]?.let { if (it.modifiedAtMs == modified) return it.config }
        }
        val text = runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
            ?: return HarnessConfig.DEFAULT
        // A corrupt or invalid file must behave exactly like "no override": the
        // defaults are the safe answer, never an exception into request assembly.
        val config = runCatching { fromJson(text)?.validated() }.getOrNull()
            ?: return HarnessConfig.DEFAULT
        synchronized(cache) { cache[key] = Entry(modified, config) }
        return config
    }

    /** True when this model carries a stored override. */
    fun hasOverride(filesDir: File?, entryId: String?): Boolean {
        val key = keyOf(entryId) ?: return false
        if (filesDir == null) return false
        return runCatching { file(filesDir, key).isFile }.getOrDefault(false)
    }

    /**
     * Persist an override for this model. Passing null restores the defaults.
     * Returns true when the stored state matches the request afterwards.
     */
    fun save(filesDir: File?, entryId: String?, config: HarnessConfig?): Boolean {
        val key = keyOf(entryId) ?: return false
        if (filesDir == null) return false
        val target = file(filesDir, key)
        if (config == null) {
            synchronized(cache) { cache.remove(key) }
            return runCatching { !target.exists() || target.delete() }.getOrDefault(false)
        }
        val valid = runCatching { config.validated() }.getOrNull() ?: return false
        return runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(toJson(valid), Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            synchronized(cache) {
                cache[key] = Entry(target.lastModified(), valid)
            }
            true
        }.getOrDefault(false)
    }

    /** Serialize every field, so a future addition is a visible diff. */
    internal fun toJson(config: HarnessConfig): String = JSONObject()
        .put("v", 1)
        .put("postAnchorBudgetTokens", config.postAnchorBudgetTokens)
        .put("postAnchorWindowFraction", config.postAnchorWindowFraction)
        .put("noticeReserveTokens", config.noticeReserveTokens)
        .put("noticeBaseTokens", config.noticeBaseTokens)
        .put("absoluteAgeTokens", config.absoluteAgeTokens)
        .put("agingCharsPerToken", config.agingCharsPerToken)
        .put("digestScanMessages", config.digestScanMessages)
        .put("digestMaxLines", config.digestMaxLines)
        .put("digestMaxChars", config.digestMaxChars)
        .put("ledgerMaxEntries", config.ledgerMaxEntries)
        .toString()

    /**
     * Parse a stored override. Every field falls back to the shipped default
     * individually, so a file written by an older build still loads.
     */
    internal fun fromJson(text: String): HarnessConfig? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val d = HarnessConfig.DEFAULT
        return HarnessConfig(
            postAnchorBudgetTokens = obj.optInt("postAnchorBudgetTokens", d.postAnchorBudgetTokens),
            postAnchorWindowFraction = obj.optDouble("postAnchorWindowFraction", d.postAnchorWindowFraction),
            noticeReserveTokens = obj.optInt("noticeReserveTokens", d.noticeReserveTokens),
            noticeBaseTokens = obj.optInt("noticeBaseTokens", d.noticeBaseTokens),
            absoluteAgeTokens = obj.optInt("absoluteAgeTokens", d.absoluteAgeTokens),
            agingCharsPerToken = obj.optLong("agingCharsPerToken", d.agingCharsPerToken),
            digestScanMessages = obj.optInt("digestScanMessages", d.digestScanMessages),
            digestMaxLines = obj.optInt("digestMaxLines", d.digestMaxLines),
            digestMaxChars = obj.optInt("digestMaxChars", d.digestMaxChars),
            ledgerMaxEntries = obj.optInt("ledgerMaxEntries", d.ledgerMaxEntries),
        )
    }

    /** Test seam. */
    internal fun clearForTest() {
        synchronized(cache) { cache.clear() }
    }
 }
