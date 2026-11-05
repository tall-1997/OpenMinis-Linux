package com.openminis.app.util

import java.io.InputStream
import java.security.MessageDigest

/**
 * [T-sha256-single-source] The app's one SHA-256 hex implementation.
 *
 * Replaces ten private/internal copies (CaBundle, ToolLoopDetector,
 * BackupBlobStore, BodyStore, MemoryRepository, SkillSubscriptionStore,
 * PendingUpdateStore, McpApprovalCore, McpApprovalExecutor,
 * ProviderConfigStore). Every one of those produced the same output — lowercase
 * hex, no prefix, UTF-8 for strings — so call sites switch verbatim and
 * file-name / consistency-check consumers keep byte-identical digests.
 *
 * Streaming reads run in 64 KiB chunks (chunk size never affects the digest).
 */
object Digest {

    private const val STREAM_CHUNK_BYTES = 64 * 1024

    /** SHA-256 of the UTF-8 encoding of [input], as lowercase hex. */
    fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

    /** SHA-256 of [bytes], as lowercase hex. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    /**
     * Streaming SHA-256 of [input] (read to EOF; the stream is NOT closed),
     * as lowercase hex. Never loads the whole payload in memory.
     */
    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(STREAM_CHUNK_BYTES)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        val digest = md.digest()
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
