package com.openminis.app.ui.chat

import android.net.Uri

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.sandbox.FileItem
import java.io.File

/**
 * Decides what should happen when a link inside chat markdown is tapped.
 *
 * Routing order:
 *  1. Recognized minis:// deep-link action  → DeepLink (delegated to MainActivity via Intent.ACTION_VIEW)
 *  2. minis://<sandbox path>, file://, or absolute /var/minis|/root path → SandboxFile
 *  3. Non-http(s) external schemes (intent://, mailto:, tel:, geo:, …)   → ExternalApp
 *  4. Anything else (http(s), about, file)                                → Web
 */
sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()
}

object ChatLinkResolver {

    fun resolve(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ChatLinkAction.Web(rawUrl)

        val uri = runCatching { trimmed.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()

        // 1. minis:// deep links — only branch out when the URL maps to a known action,
        //    otherwise fall through to sandbox-path handling.
        if (scheme == "minis") {
            val action = DeepLinkHandler.parse(uri)
            if (action !is DeepLinkAction.Unknown) {
                return ChatLinkAction.DeepLink(action)
            }
        }

        // 2. Sandbox file resolution — prefer a session-scoped resolver when
        //    the caller knows which chat this link belongs to. The global
        //    `PRootKernel.bindMounts` is last-writer-wins, so on a device
        //    with multiple sessions the resolver otherwise points at
        //    whichever session booted its shell most recently.
        val hostFile = resolveSandboxFile(trimmed, scheme, sessionId, context)
        android.util.Log.w("ChatLinkDiag",
            "resolve url=${trimmed.take(200)} sid=$sessionId hostFile=${hostFile?.absolutePath} exists=${hostFile?.exists()}")
        if (hostFile != null && hostFile.exists() && !hostFile.isDirectory) {
            FileItem.from(hostFile)?.let { return ChatLinkAction.SandboxFile(it) }
        }

        // T136: intent://, mailto:, tel:, geo:, market: etc. need a system
        // dispatch — the in-app preview WebView's `loadUrl(...)` doesn't
        // trip `shouldOverrideUrlLoading` for the initial URL, so without
        // this hop those schemes hit the WebView and surface as
        // ERR_UNKNOWN_URL_SCHEME.
        if (com.openminis.app.ui.browser.BrowserExternalSchemeHandler.shouldHandleExternally(trimmed)) {
            return ChatLinkAction.ExternalApp(trimmed)
        }

        return ChatLinkAction.Web(trimmed)
    }

    /**
     * Map a chat link to a host File when it points into the sandbox, else null.
     * Accepts:
     *   minis://attachments/foo.png        → /var/minis/attachments/foo.png
     *   minis:///var/minis/workspace/x.csv → /var/minis/workspace/x.csv (absolute)
     *   file:///path/to/file               → /path/to/file
     *   /var/minis/workspace/x.csv         → resolved via bind mount
     *   /root/whatever                     → resolved relative to rootfs
     */
    private fun resolveSandboxFile(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): File? {
        fun lookup(linuxPath: String): File? =
            if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            }
        return when (scheme) {
            "minis" -> {
                // Keep '#' — attachment filenames legitimately contain it.
                // `minis://` URLs don't use fragments, so stripping at '#'
                // would truncate filenames like `foo #China.mp4`.
                val stripped = raw.removePrefix("minis://").substringBefore('?')
                // [T-android-minis-url-double-encoding] Try each decode
                // candidate and take the first that exists on disk. See
                // [minisPathCandidates] for why one decode pass isn't enough.
                minisPathCandidates(stripped)
                    .asSequence()
                    .map { candidate ->
                        val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
                        lookup(linuxPath)
                    }
                    .firstOrNull { it != null && it.exists() }
                    // Nothing existed — hand back the primary candidate so the
                    // caller's own exists() check reports against the path the
                    // user actually meant, and diagnostics stay readable.
                    ?: lookup(
                        minisPathCandidates(stripped).first().let {
                            if (it.startsWith("/")) it else "/var/minis/$it"
                        },
                    )
            }
            "file" -> {
                val path = raw.removePrefix("file://").substringBefore('?')
                if (path.isEmpty()) null else File(java.net.URLDecoder.decode(path, "UTF-8"))
            }
            null -> {
                if (raw.startsWith("/")) lookup(raw) else null
            }
            else -> null
        }
    }

    /**
     * [T-android-minis-url-double-encoding] Decode candidates for the path part
     * of a `minis://` URL, in priority order. Ported from iOS
     * `MinisURLPathDecoding` (T-fix-double-encoding).
     *
     * A correctly-formed minis URL percent-encodes each segment exactly once,
     * and one decode pass recovers the real UTF-8 name. But links reach us
     * double-encoded when the agent — or an intermediate Markdown
     * autolink/sanitize step — re-encodes the literal `%` of an
     * already-encoded URL, turning `%E5` into `%25E5`. One decode pass then
     * yields a literal `%E5…`, which matches no file on disk.
     *
     * The user-visible symptom is a tap that does NOTHING, which is why this
     * is worth the tolerance: [resolve] falls through to `ChatLinkAction.Web`,
     * and a web preview of a `minis://` URL renders nothing at all. There is
     * no error, no toast, no navigation — the link just looks dead.
     *
     * Note this is NOT specific to CJK. Any non-ASCII segment percent-encodes
     * to `%XX` bytes and is equally affected; CJK paths merely make it far
     * more likely, since every character encodes (an ASCII path often survives
     * because it has nothing to encode in the first place).
     *
     * Disk existence is the disambiguator, so the extra candidate is only ever
     * reached when the correct single decode found nothing — a filename that
     * legitimately contains a `%` still resolves via the first candidate and
     * never sees the second decode.
     *
     * Percent-decoding is done by hand rather than with
     * [java.net.URLDecoder], which implements
     * `application/x-www-form-urlencoded` — where `+` means SPACE. A path
     * segment like `a+b/file.pdf` is a real directory name on disk, and
     * URLDecoder silently turns it into `a b/file.pdf`, resolving to nothing
     * and producing the same dead-link symptom. (`java.net.URI` is no help
     * either: its multi-arg constructor ENCODES its input, so `getPath()`
     * hands the string straight back undecoded.)
     */
    internal fun minisPathCandidates(strippedPath: String): List<String> {
        // Decode %XX only, never mapping '+' to space (see KDoc). Done by hand
        // rather than with URLDecoder (form semantics: '+' → space) or
        // java.net.URI (its multi-arg constructor ENCODES its input, so
        // getPath() hands the string straight back). Invalid escapes are
        // emitted verbatim so a stray '%' degrades instead of throwing.
        fun decodeOnce(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                        continue
                    }
                }
                // Non-escape (or malformed escape): keep the character's own
                // UTF-8 bytes so already-decoded CJK passes through intact.
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        val once = decodeOnce(strippedPath)
        val candidates = mutableListOf(once)
        val twice = decodeOnce(once)
        if (twice != once) candidates.add(twice)
        return candidates
    }

    /** Fire a system intent so MainActivity's BROWSABLE filter picks the deep link up. */
    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

// ─── Media helpers ──────────────────────────────────────────────────────────

/**
 * Resolve a markdown media URL (`minis://attachments/foo.mp4`, file://, or
 * plain absolute path) to a host File.
 *
 * First tries `PRootKernel.resolveHostPath` (same as MinisImageFetcher). If
 * that fails — e.g. bind mounts are pointing at a different session, or the
 * file was written under a `__new__...` draft id that predates
 * `ensureSession()` rename — we fall back to scanning all per-session
 * attachment directories for a file of the same basename. Mirrors the iOS
 * attach-path resolution which walks the session cache when the primary
 * lookup misses.
 */
internal fun resolveMdMediaFile(context: Context, url: String, sessionId: String? = null): File? {
    if (url.isBlank()) return null
    // Strip a real query (`?`), but NOT `#` — attachment filenames legitimately
    // contain '#' (hashtags). `minis://` URLs don't carry fragments anyway,
    // and truncating here would hide the '.mp4' extension and the file's real
    // name from the resolver.
    val stripped = url.substringBefore('?')
    val primary: File? = when {
        stripped.startsWith("minis://") -> {
            val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
            val linuxPath = "/var/minis/$decoded"
            // Prefer the session-scoped resolver when the caller supplied a
            // sessionId: the global `bindMounts` map is overwritten every time
            // another session boots its shell, so without sessionId we'd route
            // this chat's attachment lookup to whichever session happened to
            // boot last.
            if (sessionId != null) PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            else PRootKernel.resolveHostPath(linuxPath)
        }
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
    if (primary?.let { it.exists() && it.isFile } == true) {
        return primary
    }

    // Fallback stays inside this chat. Scanning every session or every project
    // by basename would show another chat's file when names collide.
    if (!stripped.startsWith("minis://") || sessionId.isNullOrBlank()) {
        return null
    }
    val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
    val basename = decoded.substringAfterLast('/')
    val subdir = decoded.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: "attachments"
    val owner = com.openminis.app.sandbox.SessionWorkspace.ownerSessionId(sessionId)
    // [T-md-media-fullpath-fallback] 先按**完整相对路径**探测：URL 里的
    // uploads/ 等子目录不能丢。旧 fallback 只按 basename 探
    // <subdir>/<name>，uploads/ 下的文件永远探不到——主路径（会话解析/
    // bindMounts）一漂移（项目目录重建、__new__ 草稿改名、shell 未起），
    // 旧消息的图就退化成破图占位，用户只能去「浏览对话文件」里找。
    val relUnderSubdir = decoded.substringAfter('/', missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() && it != basename }
    if (relUnderSubdir != null) {
        val ownFull = File(com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, subdir), relUnderSubdir)
        if (ownFull.isFile) return ownFull
        val ownerFull = File(com.openminis.app.sandbox.SessionWorkspace.base(context.filesDir, owner), "$subdir/$relUnderSubdir")
        if (ownerFull.isFile) return ownerFull
    }
    // basename 探测保留：__new__ 草稿 id 改名等「路径平移但文件名不变」的场景。
    val own = File(com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, subdir), basename)
    if (own.isFile) return own
    val privateCopy = File(com.openminis.app.sandbox.SessionWorkspace.base(context.filesDir, owner), "$subdir/$basename")
    if (privateCopy.isFile) return privateCopy
    // Also probe `minis-global/<subdir>` for shared/memory/skills buckets.
    val globalCandidate = File(context.filesDir, "minis-global/$subdir/$basename")
    if (globalCandidate.exists() && globalCandidate.isFile) {
        return globalCandidate
    }
    android.util.Log.w("MdStream", "resolveMdMediaFile url=$url -> NOT FOUND (primary=${primary?.absolutePath})")
    return null
}

internal fun filenameFromMdUrl(url: String): String {
    // Keep '#' — it's a legitimate character in attachment filenames.
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    return try { java.net.URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
}

internal fun openMdMediaExternally(context: Context, file: File, mime: String) {
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (t: Throwable) {
        android.util.Log.w("MdStream", "FileProvider failed: ${t.message}")
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    val chooser = Intent.createChooser(intent, file.name).apply {
        if (context !is android.app.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    try { context.startActivity(chooser) } catch (t: Throwable) {
        android.util.Log.w("MdStream", "startActivity failed: ${t.message}")
    }
}
