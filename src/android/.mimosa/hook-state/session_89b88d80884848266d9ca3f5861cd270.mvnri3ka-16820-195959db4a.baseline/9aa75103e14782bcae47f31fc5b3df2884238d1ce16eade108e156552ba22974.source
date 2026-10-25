package com.openminis.app.data.repository

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Remote skill feeds: URL subscribe + SHA-256 pin (integrity, not PKI) + auto-update.
 */
data class SkillFeed(
    val id: String,
    val url: String,
    val label: String,
    val sha256Pin: String?,
    val autoUpdate: Boolean,
    val lastCheckedAt: Long = 0L,
    val lastSha256: String? = null,
    val lastError: String? = null,
    val skillIds: List<String> = emptyList(),
)

object SkillSubscriptionStore {
    private const val PREFS = "skill_feeds_prefs"
    private const val KEY_FEEDS = "feeds_json"

    fun list(context: Context): List<SkillFeed> {
        val raw = prefs(context).getString(KEY_FEEDS, "[]") ?: "[]"
        return try {
            parseFeeds(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsert(context: Context, feed: SkillFeed) {
        val next = list(context).filterNot { it.id == feed.id || it.url == feed.url } + feed
        save(context, next)
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    fun save(context: Context, feeds: List<SkillFeed>) {
        val arr = JSONArray()
        for (f in feeds) arr.put(toJson(f))
        prefs(context).edit().putString(KEY_FEEDS, arr.toString()).apply()
    }

    internal fun parseFeeds(raw: String): List<SkillFeed> {
        val arr = JSONArray(raw)
        val out = ArrayList<SkillFeed>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (url.isEmpty()) continue
            val ids = ArrayList<String>()
            val idArr = o.optJSONArray("skillIds")
            if (idArr != null) {
                for (j in 0 until idArr.length()) {
                    val s = idArr.optString(j).trim()
                    if (s.isNotEmpty()) ids += s
                }
            }
            out += SkillFeed(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                url = url,
                label = o.optString("label").ifBlank { url },
                sha256Pin = o.optString("sha256Pin").trim().ifEmpty { null },
                autoUpdate = o.optBoolean("autoUpdate", true),
                lastCheckedAt = o.optLong("lastCheckedAt", 0L),
                lastSha256 = o.optString("lastSha256").trim().ifEmpty { null },
                lastError = o.optString("lastError").trim().ifEmpty { null },
                skillIds = ids,
            )
        }
        return out
    }

    internal fun parseCatalog(body: String): Catalog? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val o = JSONObject(trimmed)
            val skills = o.optJSONArray("skills") ?: return null
            val entries = ArrayList<CatalogEntry>(skills.length())
            for (i in 0 until skills.length()) {
                val s = skills.optJSONObject(i) ?: continue
                val url = s.optString("url").trim()
                if (url.isEmpty()) continue
                entries += CatalogEntry(
                    url = url,
                    sha256 = s.optString("sha256").trim().ifEmpty { null },
                    name = s.optString("name").trim().ifEmpty { null },
                )
            }
            if (entries.isEmpty()) null
            else Catalog(name = o.optString("name").trim().ifEmpty { null }, skills = entries)
        } catch (_: Exception) {
            null
        }
    }

    internal fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    internal fun pinMatches(pin: String?, actual: String): Boolean {
        val expected = pin?.trim()?.lowercase().orEmpty()
        if (expected.isEmpty()) return true
        return expected == actual.lowercase()
    }

    data class Catalog(val name: String?, val skills: List<CatalogEntry>)
    data class CatalogEntry(val url: String, val sha256: String?, val name: String?)

    private fun toJson(f: SkillFeed): JSONObject = JSONObject().apply {
        put("id", f.id)
        put("url", f.url)
        put("label", f.label)
        put("sha256Pin", f.sha256Pin ?: "")
        put("autoUpdate", f.autoUpdate)
        put("lastCheckedAt", f.lastCheckedAt)
        put("lastSha256", f.lastSha256 ?: "")
        put("lastError", f.lastError ?: "")
        put("skillIds", JSONArray(f.skillIds))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object SkillSubscriptionSync {
    private const val TAG = "SkillFeeds"
    private const val TIMEOUT_MS = 20_000

    suspend fun refreshAuto(context: Context, repo: SkillRepository) {
        val due = SkillSubscriptionStore.list(context).filter { it.autoUpdate }
        for (feed in due) {
            runCatching { refresh(context, repo, feed.id) }
                .onFailure { Log.w(TAG, "auto-update ${feed.url}: ${it.message}") }
        }
    }

    suspend fun refresh(context: Context, repo: SkillRepository, feedId: String): SkillFeed? {
        val current = SkillSubscriptionStore.list(context).firstOrNull { it.id == feedId } ?: return null
        val now = System.currentTimeMillis()
        return try {
            val body = fetch(current.url) ?: throw IllegalStateException("empty response")
            val hash = SkillSubscriptionStore.sha256Hex(body)
            if (!SkillSubscriptionStore.pinMatches(current.sha256Pin, hash)) {
                val failed = current.copy(
                    lastCheckedAt = now,
                    lastSha256 = hash,
                    lastError = "checksum mismatch (pin ${current.sha256Pin}, got $hash)",
                )
                SkillSubscriptionStore.upsert(context, failed)
                return failed
            }
            val catalog = SkillSubscriptionStore.parseCatalog(body)
            val ids = ArrayList<String>()
            if (catalog != null) {
                for (entry in catalog.skills) {
                    val skillBody = fetch(entry.url) ?: continue
                    val skillHash = SkillSubscriptionStore.sha256Hex(skillBody)
                    if (!SkillSubscriptionStore.pinMatches(entry.sha256, skillHash)) {
                        Log.w(TAG, "skip ${entry.url}: checksum mismatch")
                        continue
                    }
                    val skill = repo.importFromGitHub(entry.url)
                    if (skill != null) ids += skill.id
                }
            } else {
                val skill = repo.importFromGitHub(current.url)
                if (skill != null) ids += skill.id
            }
            val ok = current.copy(
                lastCheckedAt = now,
                lastSha256 = hash,
                lastError = if (ids.isEmpty()) "imported 0 skills" else null,
                skillIds = ids.ifEmpty { current.skillIds },
                label = catalog?.name ?: current.label,
            )
            SkillSubscriptionStore.upsert(context, ok)
            ok
        } catch (e: Exception) {
            val failed = current.copy(lastCheckedAt = now, lastError = e.message ?: "update failed")
            SkillSubscriptionStore.upsert(context, failed)
            failed
        }
    }

    private fun fetch(urlString: String): String? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "OpenMinis-Linux/1.15 skill-feed")
            setRequestProperty("Accept", "text/plain, text/markdown, application/json, */*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
        } finally {
            conn.disconnect()
        }
    }
}
