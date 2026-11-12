package com.openminis.app.sandbox

import java.io.File

/**
 * [T-path-resolver] The single host<->guest path conversion point (#22).
 *
 * Before this class the same conversion line lived in several places with
 * subtly different edge behaviour: [SessionWorkspace.resolveGuestPath] handled
 * the per-session layout, [PRootKernel.resolveHostPath] the kernel bind map +
 * rootfs fallback, [FileChangeHub] its WatchRoot(host, guestPrefix) event
 * table, and ContextOffload rebuilt its guest path from a hardcoded Linux
 * prefix. All of them answered one question — "is this path the same file
 * under the other side of the bind?" — so the arithmetic now lives here.
 *
 * A resolver is an immutable snapshot of `(guestPrefix, hostRoot)` mappings.
 * Both directions are prefix matches with a boundary check (a prefix only
 * matches itself or a descendant, never `/var/minis/workspaceX`), and every
 * host-side result is gated by [SessionWorkspace.staysInside] — the same
 * lexical containment the bind resolver has always applied — so a `..` hop
 * can map to a file INSIDE the mapped root but never out of it.
 *
 * Sources (assembled by the factories):
 *  - workspace roots — the per-session layout ([SessionWorkspace.hostDir] for
 *    every session subdir) plus the global binds (skills / shared /
 *    mcp-servers) — [forSessionLayout];
 *  - mounted folders — [MountedFolderCoordinator.bindMountSpecs], the same
 *    snapshot PRoot's `-b` argv is seeded from;
 *  - FileChangeHub watch roots — `/var/minis/mounts/sdcard` and the kernel
 *    bind map ([ofBinds] takes any `(guest -> host)` snapshot, so a caller can
 *    feed PRootKernel.bindMounts and SharedStorageBindPlan directly and
 *    combine with [forSession] below).
 *
 * The class itself is pure `java.io.File` + String arithmetic (no Android
 * imports) so the conversion rules are unit-testable on the JVM.
 */
class PathResolver private constructor(private val mappings: List<Mapping>) {

    /** One bind: [guestPrefix] (e.g. `/var/minis/workspace`) maps to [hostRoot]. */
    data class Mapping(val guestPrefix: String, val hostRoot: File)

    /**
     * Guest -> host. Longest matching guest prefix wins; null when no mapping
     * matches or the resolved file would climb out of the mapped host root.
     */
    fun toHost(guestPath: String): File? {
        if (guestPath.isEmpty()) return null
        var best: Mapping? = null
        var bestLen = -1
        for (m in mappings) {
            val prefix = m.guestPrefix
            if (prefix.isEmpty()) continue
            val matched = guestPath == prefix || guestPath.startsWith("$prefix/")
            if (matched && prefix.length > bestLen) {
                best = m
                bestLen = prefix.length
            }
        }
        val m = best ?: return null
        val tail = guestPath.substring(m.guestPrefix.length).trimStart('/')
        // '.' segments are tolerated (File resolves them); '..' hops are judged
        // by the containment check below rather than rejected wholesale — the
        // exact semantics resolveHostPath has always had (an inside hop like
        // `a/../b` resolves; `../../x` returns null).
        val candidate = if (tail.isEmpty()) m.hostRoot else File(m.hostRoot, tail)
        return candidate.takeIf { SessionWorkspace.staysInside(m.hostRoot, it) }
    }

    /**
     * Host -> guest. Longest matching host root wins (a user mount nested
     * inside the sdcard tree maps to the mount's own prefix). Null when the
     * path is not under any mapped root.
     */
    fun toGuest(hostPath: String): String? {
        var best: Mapping? = null
        var bestLen = -1
        for (m in mappings) {
            val rootLen = m.hostRoot.absolutePath.trimEnd('/', '\\').length
            if (rootLen <= bestLen) continue
            if (relUnderHost(m.hostRoot, hostPath) == null) continue
            best = m
            bestLen = rootLen
        }
        val m = best ?: return null
        val rel = relUnderHost(m.hostRoot, hostPath) ?: return null
        return if (rel.isEmpty()) m.guestPrefix else "${m.guestPrefix}/$rel"
    }

    /** Mappings for the sources this resolver does not already cover. */
    fun plus(extra: List<Mapping>): PathResolver =
        ofMappings(mappings + extra)

    companion object {

        fun ofMappings(mappings: List<Mapping>): PathResolver = PathResolver(mappings)

        /**
         * From a `(guest -> host)` snapshot — the kernel bind map, the mounted
         * folders snapshot, the shared-storage plan. Duplicate guest prefixes
         * keep the FIRST entry, so a caller that merges layout roots in before
         * external binds gets the per-session (more specific) resolution.
         */
        fun ofBinds(guestToHost: Map<String, String>): PathResolver {
            val seen = LinkedHashMap<String, Mapping>()
            for ((guest, host) in guestToHost) {
                if (guest.isEmpty() || host.isEmpty()) continue
                seen.putIfAbsent(guest, Mapping(guest, File(host)))
            }
            return PathResolver(seen.values.toList())
        }

        /**
         * The per-session layout set (workspace roots + global binds), pure
         * JVM. The host roots come from [SessionWorkspace.hostDir] — the same
         * resolution the shell bind uses — so a conversion here always agrees
         * with what the guest actually sees. Note the hostDir fallback for a
         * filed session does readdir work; a resolver snapshot should be built
         * once per operation, not per path.
         */
        fun forSessionLayout(filesDir: File, sessionId: String): PathResolver =
            PathResolver(sessionLayoutMappings(filesDir, sessionId))

        /**
         * Layout roots plus external binds (mounted folders + shared storage).
         * [externalBinds] is a `(guest -> host)` snapshot — pass
         * [MountedFolderCoordinator.bindMountSpecs] plus the
         * [SharedStorageBindPlan] entries the sandbox actually binds. Layout
         * wins on a guest-prefix collision, mirroring
         * [PRootKernel.resolveSessionHostPath]'s layout-first preference.
         */
        fun forSession(
            filesDir: File,
            sessionId: String,
            externalBinds: List<Pair<String, String>>,
        ): PathResolver {
            val merged = LinkedHashMap<String, String>()
            for (m in sessionLayoutMappings(filesDir, sessionId)) merged[m.guestPrefix] = m.hostRoot.absolutePath
            for ((guest, host) in externalBinds) merged.putIfAbsent(guest, host)
            return ofBinds(merged)
        }

        /**
         * Session layout mappings, in [SessionWorkspace] order: the per-session
         * subdirs (workspace, attachments, offloads, browser, memory) resolved
         * through [SessionWorkspace.hostDir], then the global bind subdirs.
         */
        fun sessionLayoutMappings(filesDir: File, sessionId: String): List<Mapping> {
            val out = ArrayList<Mapping>(SessionWorkspace.SESSION_SUBDIRS.size + SessionWorkspace.GLOBAL_BIND_SUBDIRS.size)
            for (subdir in SessionWorkspace.SESSION_SUBDIRS) {
                out.add(Mapping("/var/minis/$subdir", SessionWorkspace.hostDir(filesDir, sessionId, subdir)))
            }
            for (subdir in SessionWorkspace.GLOBAL_BIND_SUBDIRS) {
                out.add(Mapping("/var/minis/$subdir", File(File(filesDir, SessionWorkspace.GLOBAL_DIR), subdir)))
            }
            return out
        }

        /**
         * Root-scoped conversion primitive: the guest path of [hostPath] as
         * seen from ONE root, or null when it is not under that root. This is
         * NOT the global [toGuest]: FileChangeHub publishes events per watch
         * root, and nested mounts (a user mount inside the sdcard tree) are
         * watched by BOTH roots — each observer must publish under its own
         * prefix so consumers keep receiving the outer-prefix path too.
         */
        fun guestPathUnder(hostRoot: File, guestPrefix: String, hostPath: String): String? {
            val rel = relUnderHost(hostRoot, hostPath) ?: return null
            return if (rel.isEmpty()) guestPrefix else "$guestPrefix/$rel"
        }

        /**
         * POSIX-style path of [hostPath] relative to [hostRoot]: "" for the
         * root itself, slash-joined segments otherwise, null when [hostPath]
         * is not under [hostRoot]. Both `/` and the platform separator are
         * accepted so the JVM tests behave the same on Windows.
         */
        fun relUnderHost(hostRoot: File, hostPath: String): String? {
            val rootAbs = hostRoot.absolutePath.trimEnd('/', '\\')
            val pathAbs = hostPath.trimEnd('/', '\\')
            if (rootAbs.isEmpty() || pathAbs.isEmpty()) return null
            if (pathAbs == rootAbs) return ""
            val rel = when {
                pathAbs.startsWith("$rootAbs/") -> pathAbs.substring(rootAbs.length + 1)
                pathAbs.startsWith("$rootAbs\\") -> pathAbs.substring(rootAbs.length + 1)
                else -> return null
            }
            return rel.replace('\\', '/')
        }

        fun relUnderHost(hostRoot: File, hostPath: File): String? =
            relUnderHost(hostRoot, hostPath.absolutePath)
    }
}
