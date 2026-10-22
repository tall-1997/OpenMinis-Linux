package com.openminis.app.sandbox

import android.app.ActivityManager
import android.content.Context
import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.GuardianScript

/**
 * Guest-side ceilings handed to the shell.
 *
 * No address-space limit: RLIMIT_AS is the host's decision. The prefix runs
 * in the same shell as the command, so it must not re-exec the command on
 * fallback: `nice cmd || nice cmd` runs a pipeline twice and treats `head`'s
 * exit as success. Nothing in the prefix may kill the shell — a failed
 * rlimit is logged and ignored, because the host's clamp is authoritative
 * and an `exit 1` here is a brick.
 */
object GuestLimits {
    /**
     * [T-compile-oom-dynamic] Size every guest runtime's memory budget from
     * the DEVICE's RAM instead of fixed constants.
     *
     * Why: a JVM started with no -Xmx defaults to ~25% of physical RAM, and
     * a gradle build runs TWO of them (gradle daemon + kotlin daemon). On a
     * 16 GB phone that is ~8 GB of heap headroom inside one app process —
     * Android's LMK kills the whole app mid-compile, which is exactly the
     * "沙箱编译老是被 OOM 杀掉" report. Node had the opposite bug: a fixed
     * 192 MB old-space cap starved builds on big devices. Both ends are
     * wrong because neither scaled with the machine.
     *
     * The budgets below are caps, not allocations: heaps grow on demand, so
     * a small build never pays for the ceiling. Formulas assume the worst
     * realistic overlap — gradle daemon + kotlin daemon + the app's own
     * UI/WebView footprint — and leave the system the rest. User-set env
     * vars (Settings → Environment) are applied later by the shell and win
     * over these, which is the correct precedence: explicit user > derived.
     *
     * Mirrored for login shells in default_mount/etc/profile.d/minis.sh
     * (same formulas from /proc/meminfo) — keep the two in sync.
     */
    fun applyRuntimeMemoryBudgets(env: MutableMap<String, String>, context: Context) {
        applyForRam(env, deviceRamBytes(context))
    }

    /** RAM-explicit core, so the JVM unit tests need no Android framework. */
    internal fun applyForRam(env: MutableMap<String, String>, totalRamBytes: Long) {
        env["NODE_OPTIONS"] = nodeOptions(env["NODE_OPTIONS"], totalRamBytes)
        env["_JAVA_OPTIONS"] = javaOptions(env["_JAVA_OPTIONS"], totalRamBytes)
        env["GRADLE_OPTS"] = gradleOpts(env["GRADLE_OPTS"], totalRamBytes)
        // Native toolchains: parallelism by RAM, not core count — a
        // make -j$(nproc) on a big-core phone spawns one cc1plus per core.
        env.putIfAbsent("MAKEFLAGS", "-j${nativeJobs(totalRamBytes)}")
        env.putIfAbsent("CMAKE_BUILD_PARALLEL_LEVEL", "${nativeJobs(totalRamBytes)}")
    }

    /** Total device RAM; a sane mid-range fallback if the query fails. */
    fun deviceRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 8L * GB
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }
        return info.totalMem.takeIf { it > 0 } ?: 8L * GB
    }

    /** Node old-space: RAM/12, clamped 192..768 MB. */
    fun nodeOptions(existing: String? = null, totalRamBytes: Long = 8L * GB): String {
        val flag = "--max-old-space-size=${(totalRamBytes / (12L * MB)).toInt().coerceIn(192, 768)}"
        val stripped = existing.orEmpty()
            .replace(Regex("""--max-old-space-size(?:=|\s+)\d+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (stripped.isEmpty()) flag else "$stripped $flag"
    }

    /**
     * JVM heap for EVERY guest JVM (gradle daemon, kotlin daemon, kotlinc,
     * mvn, plain java): RAM/8, clamped 768..2048 MB, plus a metaspace cap —
     * Kotlin compiles balloon metaspace as well as heap. RAM/8 rather than
     * the JVM's own /4 default because two daemons run concurrently.
     */
    fun javaOptions(existing: String? = null, totalRamBytes: Long = 8L * GB): String {
        val heapMb = (totalRamBytes / (8L * MB)).toInt().coerceIn(768, 2048)
        val stripped = existing.orEmpty()
            .replace(Regex("""-Xmx\s*\d+[kKmMgG]?"""), " ")
            .replace(Regex("""-XX:MaxMetaspaceSize=\S+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val mine = "-Xmx${heapMb}m -XX:MaxMetaspaceSize=512m"
        return if (stripped.isEmpty()) mine else "$stripped $mine"
    }

    /** Gradle worker count: RAM per 4 GB, clamped 2..6. */
    fun gradleOpts(existing: String? = null, totalRamBytes: Long = 8L * GB): String {
        val flag = "-Dorg.gradle.workers.max=${(totalRamBytes / (4L * GB)).toInt().coerceIn(2, 6)}"
        val stripped = existing.orEmpty()
            .replace(Regex("""-Dorg\.gradle\.workers\.max=\d+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (stripped.isEmpty()) flag else "$stripped $flag"
    }

    /** Native compile jobs (make/ninja/cmake): RAM per 3 GB, clamped 2..6. */
    fun nativeJobs(totalRamBytes: Long = 8L * GB): Int =
        (totalRamBytes / (3L * GB)).toInt().coerceIn(2, 6)

    private const val MB = 1024L * 1024
    private const val GB = 1024L * MB

    /**
     * One-shot `bash -c` / `su -c` guardian. Hard rlimits, a process-group
     * watchdog, and an EXIT trap that reaps only that watchdog.
     */
    fun wrap(shellCommand: String): String =
        GuardianScript.oneshot(shellCommand, BudgetClassifier.classify(shellCommand))

    /**
     * Do not call this from [PersistentShell]. A subshell drops cwd and
     * exports, and an EXIT trap on that shell kills the pipe reader.
     */
    @Deprecated(
        "PersistentShell must use GuardianScript.persistentCommand. A subshell wrap is the failure.",
        level = DeprecationLevel.ERROR,
    )
    fun wrapChild(shellCommand: String): String =
        throw IllegalStateException("wrapChild($shellCommand) is forbidden on the persistent shell")

    /** Interactive terminal: rlimits only. No watchdog, so a hang cannot kill the user's shell. */
    fun interactivePrelude(): String = GuardianScript.bootLimits(BudgetClassifier.service())
}
