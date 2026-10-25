package com.openminis.app.sandbox

import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Host-side readable `/proc` snapshot. Android hidepid hides other UIDs;
 * this lists only what this app can actually read — never walks ubuntu-rootfs.
 */
object ProcfsSnapshot {

    const val MAX_PROCS = 256

    data class ProcInfo(
        val pid: Int,
        val comm: String,
        val cmdline: String,
        val uid: Int?,
    )

    fun parseStatComm(stat: String): String? {
        val i = stat.indexOf('(')
        val j = stat.lastIndexOf(')')
        if (i < 0 || j <= i) return null
        return stat.substring(i + 1, j)
    }

    fun parseStatusUid(status: String): Int? {
        val line = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return null
        return line.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
    }

    fun listReadable(procRoot: File = File("/proc")): List<ProcInfo> {
        val dirs = procRoot.listFiles() ?: return emptyList()
        val out = ArrayList<ProcInfo>(64)
        for (dir in dirs) {
            if (out.size >= MAX_PROCS) break
            val pid = dir.name.toIntOrNull() ?: continue
            val statFile = File(dir, "stat")
            if (!statFile.canRead()) continue
            val stat = runCatching { statFile.readText() }.getOrNull() ?: continue
            val comm = parseStatComm(stat) ?: continue
            val cmdline = runCatching {
                File(dir, "cmdline").takeIf { it.canRead() }?.readBytes()
                    ?.toString(Charsets.UTF_8)
                    ?.replace('\u0000', ' ')
                    ?.trim()
                    ?.take(200)
                    .orEmpty()
            }.getOrDefault("")
            val uid = runCatching {
                File(dir, "status").takeIf { it.canRead() }?.readText()?.let(::parseStatusUid)
            }.getOrNull()
            out += ProcInfo(pid, comm, cmdline, uid)
        }
        return out.sortedBy { it.pid }
    }

    fun snapshot(procRoot: File = File("/proc")): JSONObject {
        val procs = listReadable(procRoot)
        val arr = JSONArray()
        for (p in procs) {
            arr.put(
                JSONObject()
                    .put("pid", p.pid)
                    .put("comm", p.comm)
                    .put("cmdline", p.cmdline)
                    .put("uid", p.uid ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("self_pid", Process.myPid())
            .put("self_uid", Process.myUid())
            .put("hidepid", "other UIDs are invisible on Android")
            .put("readable_count", procs.size)
            .put("processes", arr)
    }

    fun table(procRoot: File = File("/proc")): String {
        val procs = listReadable(procRoot)
        val sb = StringBuilder()
        sb.append("PID\tUID\tCOMM\tCMDLINE\n")
        for (p in procs) {
            sb.append(p.pid).append('\t')
                .append(p.uid ?: "-").append('\t')
                .append(p.comm).append('\t')
                .append(p.cmdline).append('\n')
        }
        return sb.toString()
    }
}
