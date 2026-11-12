// [OpenMinis#439 / #440] native_offload handler stubs + shell wrappers.
package com.openminis.app.sandbox

import android.util.Log
import java.io.File

/**
 * Extract of PRootKernel's `/usr/local/bin` provisioning (architecture-policy
 * line ceiling). Two responsibilities:
 *
 *  1. [installOffloadHandlerStubs] — create the `exit 0` stub for every
 *     registered native_offload handler so the shell finds something on PATH
 *     to trigger the execve proot's extension intercepts.
 *  2. the busybox `top` wrapper that works around Android procfs hidepid.
 */

private const val TAG = "PRootKernel"

/**
 * Create a stub executable inside the rootfs for every registered
 * native_offload handler.
 *
 * [OpenMinis#439 / #440] In the degraded state (server could not bind for any
 * reason — a per-process socket makes the old clone/second-user collision
 * disappear, so this is now a defensive path) NO stub may remain on PATH: the
 * stub body is `exit 0`, so without the proot interception every handler
 * command would become a silent empty success instead of "command not found".
 * Passing an empty set through the same path also deletes stale stubs by their
 * content signature.
 */
internal fun installOffloadHandlerStubs(rootfsDir: File) {
    val binDir = File(rootfsDir, "usr/local/bin").also { it.mkdirs() }
    val registered = if (NativeOffloadServer.isListening) {
        NativeOffloadServer.registeredHandlers
    } else {
        Log.w(TAG, "[OpenMinis#439/#440] native offload is degraded — installing no handler stubs")
        emptySet<String>()
    }
    // The stub is fixed text, so a file matching it byte-for-byte was written
    // by THIS mechanism. Anything else in the dir (a real binary, a guard/`top`
    // wrapper) has different content and is never touched.
    val stubBody = "#!/bin/sh\nexit 0\n"
    var created = 0
    var existed = 0
    var removed = 0
    for (name in registered) {
        val stub = File(binDir, name)
        if (stub.exists()) { existed++; continue }
        stub.writeText(stubBody)
        stub.setExecutable(true, false)
        created++
    }
    // [T-stale-handler-stubs] A renamed/retired command kept its `exit 0` stub
    // forever: the guest got a silent EMPTY success — the worst dead end for an
    // agent, no error to react to. Delete stubs (by exact content signature)
    // whose name is no longer registered.
    binDir.listFiles()?.forEach { f ->
        if (!f.isFile || f.name in registered) return@forEach
        val body = runCatching { f.readText() }.getOrNull() ?: return@forEach
        if (body == stubBody && f.delete()) removed++
    }
    Log.d(TAG, "installHandlerStubs dir=${binDir.absolutePath} created=$created existed=$existed removed=$removed")
    installShellWrappers(binDir)
}

/**
 * Install /usr/local/bin/ wrapper scripts that work around Android-specific
 * sandbox limitations. These take precedence over /bin/busybox in PATH.
 *
 * top: Android's hidepid=2 + SELinux blocks reads of /proc/<other_pid>/stat for
 * non-system UIDs, so plain `top` fails with "can't open 'stat': Permission
 * denied". Restrict it transparently to the PIDs we can actually read.
 *
 * Always rewritten so improvements ship with each app update.
 */
private fun installShellWrappers(binDir: File) {
    // busybox `top` aborts on the first unreadable /proc/<pid>/stat (Android
    // blocks other UIDs' procfs) and takes no `-p PIDS`. Simulate it with `ps`
    // in a refresh loop, scoped to readable PIDs.
    val topWrapper = File(binDir, "top")
    topWrapper.writeText(
        """#!/bin/sh
        |# Auto-installed by MinisApp PRootKernel.
        |# busybox top walks all of /proc and aborts on the first unreadable
        |# entry under Android's procfs hidepid restrictions, and this build
        |# of busybox doesn't accept `-p`. Emulate `top` with `ps` over the
        |# PIDs we can actually inspect.
        |
        |# Parse a few common top-style flags so existing muscle memory works.
        |delay=2
        |iters=-1
        |batch=0
        |while [ ${'$'}# -gt 0 ]; do
        |    case "${'$'}1" in
        |        -d) delay=${'$'}2; shift 2;;
        |        -n) iters=${'$'}2; shift 2;;
        |        -b) batch=1; shift;;
        |        -h|--help)
        |            echo "Usage: top [-b] [-n COUNT] [-d SECONDS]"
        |            exit 0;;
        |        *) shift;;
        |    esac
        |done
        |
        |saved_stty=""
        |on_exit() {
        |    [ ${'$'}batch -eq 0 ] && printf '\033[?25h'
        |    [ -n "${'$'}saved_stty" ] && stty "${'$'}saved_stty" 2>/dev/null
        |}
        |trap on_exit INT TERM EXIT
        |
        |list_readable_pids() {
        |    for d in /proc/[0-9]*; do
        |        [ -r "${'$'}d/stat" ] && printf '%s\n' "${'$'}{d##*/}"
        |    done
        |}
        |
        |render_once() {
        |    pids=${'$'}(list_readable_pids | tr '\n' ',' | sed 's/,${'$'}//')
        |    if [ -z "${'$'}pids" ]; then
        |        echo "top: no readable processes in /proc (Android sandbox)"
        |        return
        |    fi
        |    uptime_s=${'$'}(awk '{print int(${'$'}1)}' /proc/uptime 2>/dev/null)
        |    [ -z "${'$'}uptime_s" ] && uptime_s=0
        |    days=${'$'}((uptime_s / 86400))
        |    hours=${'$'}(((uptime_s % 86400) / 3600))
        |    mins=${'$'}(((uptime_s % 3600) / 60))
        |    visible=${'$'}(echo "${'$'}pids" | tr ',' '\n' | wc -l)
        |    printf 'top — up %dd %02d:%02d  visible processes: %d (own session only)\n' "${'$'}days" "${'$'}hours" "${'$'}mins" "${'$'}visible"
        |    printf '%s\n' '  PID USER     STAT  RSS  PPID COMMAND'
        |    /bin/busybox ps -o pid,user,stat,rss,ppid,comm 2>/dev/null \
        |        | awk -v pids=",${'$'}pids," 'NR==1 {next} {if (index(pids,","${'$'}1",")) print "  " ${'$'}0}'
        |}
        |
        |if [ ${'$'}batch -eq 1 ]; then
        |    count=0
        |    while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
        |        render_once
        |        count=${'$'}((count + 1))
        |        [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
        |        sleep "${'$'}delay"
        |    done
        |    exit 0
        |fi
        |
        |# Interactive mode: clear screen + redraw each interval. Exits on
        |# Ctrl+C OR when the user presses 'q' / 'Q'. We put the TTY into
        |# raw, no-echo mode so single keypresses are read without Enter.
        |if [ -t 0 ]; then
        |    saved_stty=${'$'}(stty -g 2>/dev/null)
        |    stty -echo -icanon min 0 time 0 2>/dev/null
        |fi
        |printf '\033[?25l'
        |count=0
        |while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
        |    printf '\033[H\033[2J'
        |    render_once
        |    count=${'$'}((count + 1))
        |    [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
        |    # Sleep in small slices so 'q' is responsive within ~0.1s.
        |    end=${'$'}((${'$'}(date +%s) + delay))
        |    while [ ${'$'}(date +%s) -lt ${'$'}end ]; do
        |        if [ -t 0 ]; then
        |            ch=${'$'}(dd bs=1 count=1 2>/dev/null)
        |            case "${'$'}ch" in q|Q) exit 0;; esac
        |        fi
        |        sleep 0.1
        |    done
        |done
        |""".trimMargin()
    )
    topWrapper.setExecutable(true, false)
    Log.d(TAG, "installShellWrappers: /usr/local/bin/top installed")
}
