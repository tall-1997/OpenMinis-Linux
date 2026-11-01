package com.openminis.app.a2a

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * [T-a2a-glibc] Deploys the bundled glibc runtime tarball
 * (`a2a-glibc.tar.gz`, built by `scripts/build_a2a_glibc.sh`) from APK
 * assets into `<home>/glibc/`.
 *
 * The tarball carries the runtime the provider plugins exec under: the
 * loader (`ld-linux-aarch64.so.1`), the libc family, and the zoneinfo
 * tree (`glibc/zoneinfo/` — the target of the gateway's `TZDIR`). It is
 * produced by GNU tar in ustar format; parsing is done here (a ~120-line
 * table reader) instead of pulling a tar dependency into the app.
 *
 * Deployment is atomic and verified the same way as the panel bundle:
 * extract into a staging dir, re-verify every file's size against a dry
 * parse of the same stream, then swap the dir in with a rename. A torn
 * extraction can never leave a half runtime next to a live gateway.
 *
 * Archive shape is normalized, not assumed: a tarball packed as
 * `tar czf x.tgz out/` prefixes every entry with `out/` — the deployer
 * strips that shared top-level directory ([topDirOf] + [deployName]) so
 * the runtime always lands directly under `<home>/glibc/` (the layout
 * [A2aGatewayProcess.launchEnv]'s TZDIR and [A2aInstaller.isDeployed]
 * pin). GNU tar also preserves file modes; the owner-exec bit is applied
 * on extraction — the wrapper's `exec "$loader"` needs an executable
 * loader, and Android's file APIs honor no umask.
 *
 * The tarball carries no PAX/GNU long-name entries (the build script's
 * paths are all < 100 bytes); the parser still rejects them loudly rather
 * than guessing — a silent misparse would publish a truncated libc, which
 * is exactly the class of bug the verification pass is for.
 */
object A2aGlibcDeploy {
    private const val BLOCK = 512
    // 0x8b exceeds Byte's signed range, so these are runtime vals (const
    // initializers cannot call toByte()).
    private val GZIP_MAGIC_HI = 0x1f.toByte()
    private val GZIP_MAGIC_LO = 0x8b.toByte()

    /** One parsed ustar header. */
    internal data class TarEntry(
        val name: String,
        val size: Long,
        val typeFlag: Char,
        val linkTarget: String,
        val mode: Int,
    ) {
        val isDirectory: Boolean get() = typeFlag == '5'
        val isSymlink: Boolean get() = typeFlag == '2'
        val isHardlink: Boolean get() = typeFlag == '1'
        val isFile: Boolean get() = typeFlag == '0' || typeFlag == '\u0000'
    }

    /**
     * Parses one 512-byte ustar header block. Pure — the JVM test pins
     * name/size/type/link parsing byte-for-byte. Returns null for a
     * zero-filled block (end-of-archive marker) and throws on anything
     * this deployer does not support (long-name entries).
     */
    internal fun parseHeader(block: ByteArray): TarEntry? {
        require(block.size >= BLOCK) { "short tar header" }
        if (block.all { it == 0.toByte() }) return null
        val name = asciiField(block, 0, 100)
        val size = octalField(block, 124, 12)
        val typeFlag = block[156].toInt().toChar()
        val link = asciiField(block, 157, 100)
        val mode = octalField(block, 100, 8).toInt()
        require(block[257] == 'u'.code.toByte() || asciiField(block, 257, 6).isEmpty()) {
            "not a ustar archive (magic mismatch)"
        }
        val prefix = asciiField(block, 345, 155)
        val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
        require(typeFlag != 'L' && typeFlag != 'x' && typeFlag != 'g') {
            "unsupported tar entry type '$typeFlag' for $fullName"
        }
        return TarEntry(fullName, size, typeFlag, link, mode)
    }

    /** True when the stream is an end-of-archive all-zero block. */
    internal fun isEndBlock(block: ByteArray): Boolean =
        block.size >= BLOCK && block.take(BLOCK).all { it == 0.toByte() }

    /**
     * The single top-level directory every entry name starts with — the
     * `tar czf x.tgz out/` shape, where GNU tar prefixes every name and
     * the archive root carries the `out/` directory entry itself. Returns
     * null when the archive is already flat (no shared prefix). Pure.
     */
    internal fun topDirOf(names: Collection<String>): String? {
        val first = names.firstOrNull() ?: return null
        val dir = first.substringBefore('/')
        if (dir.isEmpty() || dir == first) return null
        return if (names.all { it == dir || it.startsWith("$dir/") }) dir else null
    }

    /**
     * Archive name mapped to its deploy-relative form: strips the shared
     * top-level directory [topDir] (a no-op when null). The root
     * directory entry maps to the empty string — the caller skips it.
     * Pure.
     */
    internal fun deployName(name: String, topDir: String?): String =
        when {
            topDir == null -> name
            name == topDir -> ""
            else -> name.removePrefix("$topDir/")
        }

    /**
     * Dry-parse pass: every file entry name mapped to its declared size —
     * the extraction's own table of truth, mirroring the panel bundle's
     * [A2aInstaller.expectedSizes]. The deploy reads the gzip stream twice;
     * a tarball small enough to ship in an APK is cheap enough to scan.
     */
    internal fun expectedEntries(input: InputStream): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        openArchive(input).use { stream ->
            while (true) {
                val block = readBlock(stream) ?: break
                val entry = parseHeader(block) ?: break
                if (entry.isFile) out[entry.name] = entry.size
                skipPayload(stream, entry.size)
            }
        }
        return out
    }

    /**
     * Extract a gzip'd ustar stream into [staging]. [topDir] (from
     * [topDirOf]) strips the archive's shared top-level directory so the
     * runtime lands flat in the staging root. Zip-slip guard on every
     * entry (absolute / `..` names are rejected). Directories, regular
     * files and symlinks are created; hardlinks are resolved against the
     * staged tree (the zoneinfo tarball does not use them, but the parser
     * supports the type and extracting it wrongly is worse than supporting
     * it). Regular files get their owner-exec bit applied — the wrapper's
     * `exec "$loader"` requires an executable loader. Returns the list of
     * problems (empty = clean).
     */
    internal fun extract(input: InputStream, staging: File, topDir: String?): List<String> {
        val problems = mutableListOf<String>()
        try {
            openArchive(input).use { stream ->
                while (true) {
                    val block = readBlock(stream) ?: break
                    val entry = parseHeader(block) ?: break
                    val name = deployName(entry.name, topDir)
                    if (name.isEmpty() && entry.isDirectory) {
                        // The archive's root directory entry itself —
                        // staging already exists, nothing to create.
                        skipPayload(stream, entry.size)
                        continue
                    }
                    val target = deployTarget(staging, name)
                    if (target == null) {
                        problems += "rejected entry ${entry.name}"
                        skipPayload(stream, entry.size)
                        continue
                    }
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { out ->
                                copyExactly(stream, out, entry.size)
                            }
                            // ustar carries the mode; apply the owner-exec
                            // bit or the wrapper's `exec "$loader"` dies
                            // with EACCES on a 0644 file.
                            if (entry.mode and 0b001_000_000 != 0) {
                                target.setExecutable(true, false)
                            }
                        }
                        entry.isSymlink -> {
                            target.parentFile?.mkdirs()
                            runCatching { target.delete() }
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    target.toPath(),
                                    java.nio.file.Paths.get(entry.linkTarget),
                                )
                            } catch (t: Throwable) {
                                problems += "symlink ${entry.name}: ${t.message}"
                            }
                        }
                        entry.isHardlink -> {
                            val source = deployTarget(staging, entry.linkTarget)
                            if (source == null || !source.isFile) {
                                problems += "hardlink ${entry.name} -> ${entry.linkTarget}"
                            } else {
                                target.parentFile?.mkdirs()
                                runCatching { target.delete() }
                                try {
                                    java.nio.file.Files.createLink(target.toPath(), source.toPath())
                                } catch (t: Throwable) {
                                    // Fall back to a copy — same bytes, no
                                    // cross-device link semantics needed here.
                                    source.copyTo(target, overwrite = true)
                                }
                            }
                        }
                        else -> problems += "unsupported entry ${entry.name}"
                    }
                }
            }
        } catch (t: Throwable) {
            problems += (t.message ?: t.javaClass.simpleName).take(200)
        }
        return problems
    }

    /**
     * Size-for-size check between the dry parse [expected] and the files
     * under [stagedRoot]: every file entry must exist with EXACTLY its
     * declared size — a truncated extraction fails here, before the
     * destructive swap. Same contract as the panel bundle's verify.
     */
    internal fun verify(stagedRoot: File, expected: Map<String, Long>): List<String> {
        val problems = mutableListOf<String>()
        for ((name, size) in expected) {
            val file = File(stagedRoot, name)
            when {
                !file.isFile -> problems += "missing $name"
                file.length() != size -> problems += "short $name (${file.length()}/$size)"
            }
        }
        return problems
    }

    /**
     * Wraps the archive stream by sniffing the gzip magic instead of
     * trusting the pipeline's packaging choice. CI's build script emits a
     * `.tar.gz`, but a pipeline that stages the tarball without gzip
     * ships a plain `.tar` — both deploy. The mark/reset keeps the sniff
     * non-destructive: a plain stream is returned with its first bytes
     * intact, a gzip stream unwraps through [GZIPInputStream].
     */
    internal fun openArchive(input: InputStream): InputStream {
        val buffered = input.buffered()
        buffered.mark(2)
        val magic = ByteArray(2)
        val read = buffered.read(magic)
        buffered.reset()
        val gzipped = read == 2 && magic[0] == GZIP_MAGIC_HI && magic[1] == GZIP_MAGIC_LO
        return if (gzipped) GZIPInputStream(buffered) else buffered
    }

    private fun readBlock(stream: InputStream): ByteArray? {
        val block = ByteArray(BLOCK)
        var off = 0
        while (off < BLOCK) {
            val n = stream.read(block, off, BLOCK - off)
            if (n < 0) {
                if (off == 0) return null
                throw java.io.EOFException("tar truncated mid-header")
            }
            off += n
        }
        return block
    }

    /** Skips exactly [count] bytes; performs no block alignment. */
    private fun skipExactly(stream: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw java.io.EOFException("tar truncated mid-payload")
            remaining -= n
        }
    }

    /** Skips [size] payload bytes plus the zero padding up to the next block boundary. */
    private fun skipPayload(stream: InputStream, size: Long) {
        // Recursing through the same routine for the padding is a loop:
        // padding of 490 yields padding of 22, and so on until the stream
        // runs out — so the alignment is computed once, in the caller's
        // size space. Callers that already hold an aligned byte count must
        // use [skipExactly] instead; feeding aligned bytes back through
        // this routine double-counts the padding and drifts the stream.
        skipExactly(stream, size + (BLOCK - (size % BLOCK)) % BLOCK)
    }

    private fun copyExactly(stream: InputStream, out: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw java.io.EOFException("tar truncated mid-file")
            out.write(buffer, 0, n)
            remaining -= n
        }
        val padding = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
        if (padding > 0) skipExactly(stream, padding.toLong())
    }

    private fun asciiField(block: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { block[it] == 0.toByte() }
            ?: (offset + length)
        return block.copyOfRange(offset, end).toString(Charsets.ISO_8859_1)
    }

    private fun octalField(block: ByteArray, offset: Int, length: Int): Long {
        val text = asciiField(block, offset, length).trim()
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8)
            ?: throw IllegalArgumentException("bad octal field '$text'")
    }

    private fun deployTarget(staging: File, name: String): File? {
        if (name.isBlank()) return null
        val target = File(staging, name)
        val canonical = target.canonicalPath
        if (!canonical.startsWith(staging.canonicalPath + File.separator)) return null
        return target
    }
}
