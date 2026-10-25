package com.openminis.app.data

/**
 * Pure version / rolling-channel rules for [UpdateChecker].
 *
 * Semver tags (`1.17-linux`) compare as today. The fork's rolling pre-release
 * is always tagged `android-latest`; after [normalizeTag] that becomes
 * `"android"` and would lexicographically beat `"1.16"`. Rolling updates are
 * therefore decided by `versionCode` / `versionName` in the release body and,
 * as a last resort, the APK asset's `updated_at` vs the installed APK's
 * `lastUpdateTime`.
 */
object UpdateVersionLogic {

    const val ROLLING_TAG = "android-latest"
    const val ROLLING_SLOP_MS = 60_000L

    data class ReleaseCandidate(
        val tagName: String,
        val versionName: String,
        val releaseName: String,
        val changelog: String,
        val apkUrl: String?,
        val apkSize: Long,
        val apkUpdatedAtMs: Long,
        val bodyVersionCode: Int?,
        val bodyVersionName: String?,
        /** GitHub asset sha256 (bare hex) — null when the API omitted the digest. */
        val apkSha256: String? = null,
    )

    fun isRollingTag(tag: String): Boolean =
        tag.equals(ROLLING_TAG, ignoreCase = true)

    fun normalizeTag(tag: String): String {
        val trimmed = tag.trim().removePrefix("v").removePrefix("V")
        val dashIdx = trimmed.indexOf('-')
        return if (dashIdx > 0) trimmed.substring(0, dashIdx) else trimmed
    }

    fun compareVersions(a: String, b: String): Int {
        val ap = a.split('.', '-')
        val bp = b.split('.', '-')
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val x = ap.getOrNull(i) ?: ""
            val y = bp.getOrNull(i) ?: ""
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    fun parseVersionCodeFromBody(body: String): Int? {
        // Separator class includes U+FF1A (full-width colon): the rolling
        // CI body writes `- versionCode：239`, and the ASCII-only class
        // silently returned null, which pushed every decision onto the
        // asset-timestamp heuristic below.
        return Regex("""(?im)(?:^|\b)versionCode\s*[:=：]\s*(\d+)""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    fun parseVersionNameFromBody(body: String): String? {
        return Regex("""(?im)(?:^|\b)versionName\s*[:=：]\s*`?([0-9][0-9A-Za-z.+_-]*)`?""")
            .find(body)
            ?.groupValues
            ?.get(1)
    }

    fun parseGithubTime(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    fun displayVersion(c: ReleaseCandidate): String {
        if (isRollingTag(c.tagName)) {
            val fromBody = c.bodyVersionName?.trim().orEmpty()
            if (fromBody.isNotEmpty()) return fromBody
            return c.tagName
        }
        return c.versionName
    }

    fun isNewerThanLocal(
        c: ReleaseCandidate,
        localVer: String,
        localCode: Int,
        localLastUpdateMs: Long,
    ): Boolean {
        if (c.apkUrl.isNullOrEmpty()) return false
        if (isRollingTag(c.tagName)) {
            val bodyCode = c.bodyVersionCode
            val bodyVer = c.bodyVersionName?.let { normalizeTag(it) }
            if (bodyCode != null && bodyCode > localCode) return true
            if (bodyVer != null && compareVersions(bodyVer, localVer) > 0) return true
            if (bodyCode == null) {
                val apkName = bodyVer ?: normalizeTag(c.versionName)
                // Fail closed unless the name actually looks like a version.
                // Without this guard, a rolling body we cannot parse yields
                // apkName == "android", which lexicographically beats every
                // digit-leading local version ("a" > "2") — the timestamp
                // alone then falsely claims an update for users already on
                // the newest build.
                val versionLike = apkName.firstOrNull()?.isDigit() == true
                if (versionLike &&
                    c.apkUpdatedAtMs > 0L &&
                    localLastUpdateMs > 0L &&
                    c.apkUpdatedAtMs > localLastUpdateMs + ROLLING_SLOP_MS &&
                    compareVersions(apkName, localVer) > 0
                ) {
                    return true
                }
            }
            return false
        }
        return compareVersions(c.versionName, localVer) > 0
    }

    fun pickUpgrade(
        candidates: List<ReleaseCandidate>,
        localVer: String,
        localCode: Int,
        localLastUpdateMs: Long,
    ): ReleaseCandidate? {
        val newer = candidates.filter {
            isNewerThanLocal(it, localVer, localCode, localLastUpdateMs)
        }
        if (newer.isEmpty()) return null
        // Compare candidates to each other. Ranking everyone against "0" collapses
        // every version > 0 to the same Int and then bodyVersionCode would always
        // prefer a rolling build over a higher semver tag.
        return newer.maxWithOrNull(
            Comparator { a, b ->
                val byName = compareVersions(
                    normalizeTag(displayVersion(a)),
                    normalizeTag(displayVersion(b)),
                )
                if (byName != 0) {
                    byName
                } else {
                    val byCode = (a.bodyVersionCode ?: -1).compareTo(b.bodyVersionCode ?: -1)
                    if (byCode != 0) byCode else a.apkUpdatedAtMs.compareTo(b.apkUpdatedAtMs)
                }
            },
        )
    }

    fun highestPublished(candidates: List<ReleaseCandidate>): ReleaseCandidate? {
        val semver = candidates.filter { !isRollingTag(it.tagName) }
        val pool = semver.ifEmpty { candidates }
        // Pairwise comparison, NOT a score against "0": ranking everyone
        // against a constant collapses e.g. both "1.0" and "1.0.1" to the
        // same score (first component decides and both beat 0), so the
        // winner depended on list order rather than the version ordering.
        return pool.maxWithOrNull(Comparator { a, b ->
            compareVersions(a.versionName, b.versionName)
        })
    }

    /** Drop CI metadata lines so the dialog can show real release notes. */
    fun stripReleaseMetadata(body: String): String {
        if (body.isBlank() || body.equals("null", ignoreCase = true)) return ""
        val metadata = Regex(
            """(?i)^\s*(?:[-*]\s*)?(?:versionCode|versionName|applicationId)\s*[:=：].*$""",
        )
        return body.replace("\r\n", "\n")
            .lineSequence()
            .filterNot { metadata.matches(it) }
            .joinToString("\n")
            .trim()
    }

    /**
     * Rolling `android-latest` bodies are often just versionCode/versionName.
     * Prefer the matching tagged release's notes when the chosen body is thin.
     */
    fun resolveChangelog(chosen: ReleaseCandidate, all: List<ReleaseCandidate>): String {
        val own = stripReleaseMetadata(chosen.changelog)
        val target = normalizeTag(displayVersion(chosen))
        fun sameVersion(other: ReleaseCandidate): Boolean {
            if (other.tagName == chosen.tagName) return false
            if (isRollingTag(other.tagName)) return false
            return normalizeTag(other.versionName) == target ||
                other.bodyVersionName?.let { normalizeTag(it) } == target ||
                normalizeTag(other.tagName) == target
        }
        val sibling = all.firstNotNullOfOrNull { other ->
            if (!sameVersion(other)) null
            else stripReleaseMetadata(other.changelog).takeIf { it.isNotBlank() }
        }
        return when {
            // The rolling body is CI boilerplate by construction — it is
            // never curated notes. When a same-version tagged release exists,
            // its notes are the honest changelog even if the boilerplate
            // happens to be longer than the 80-char heuristic.
            isRollingTag(chosen.tagName) -> sibling ?: own
            own.length >= 80 -> own
            sibling != null && sibling.length > own.length -> sibling
            own.isNotBlank() -> own
            else -> sibling.orEmpty()
        }
    }
}
