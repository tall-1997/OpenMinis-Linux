package com.openminis.app.platform

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Which process of the package is this?
 *
 * Android spawns one Linux process per `android:process="…"` component and
 * runs the SAME [android.app.Application] subclass in every one of them, so
 * `Application.onCreate` has to answer "am I the main process?" before it
 * does anything that is only valid once per app (binding the sandbox's
 * abstract socket, opening the database, booting PRoot, …).
 *
 * [isMainProcess] is the generic answer; it costs one small file read and is
 * safe to call at the very top of `onCreate`. Sub-processes (`:a2a`, `:acra`,
 * any future ones) inherit the "minimal boot" path instead of rediscovering
 * the collision the hard way.
 */
object ProcessChecks {

    /**
     * True when the current process is the package's MAIN (default) process —
     * i.e. [Context.packageName] with no `:` suffix.
     *
     * Detection ladder, cheapest first, all of it local to this process:
     *  1. [Application.getProcessName] (API 28+, framework-maintained).
     *  2. `/proc/self/cmdline` — the kernel's own answer, available on every
     *     API level we ship to and immune to the ActivityManager binder
     *     being busy during a cold start.
     *  3. [ActivityManager.getRunningAppProcesses] — the classic fallback.
     *     On API 26+ a third-party app only ever sees its own UID's entries,
     *     so this leaks nothing about other packages.
     *
     * Every fallback failure resolves to "main": a broken detection must
     * never silently disable essential subsystems.
     */
    fun isMainProcess(context: Context): Boolean =
        currentProcessName(context) == context.packageName

    /** This process's name — the package name for the main process. */
    fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            frameworkProcessName()?.let { return it }
        }
        cmdlineProcessName()?.let { return it }
        activityManagerProcessName(context)?.let { return it }
        return context.packageName
    }

    /**
     * Convenience for `Application.onCreate`: true when this process must skip
     * the main-process boot ([T-android-a2a-process-crash]).
     *
     * Everything the main process owns — the sandbox's single-instance
     * abstract socket, PRoot, the database, the repositories, the WebView data
     * directory — collides or double-boots if a second process runs the same
     * init, so `android:process` components (`:a2a` panel, any future one) get
     * a minimal boot instead. ACRA is armed in `attachBaseContext`, so crash
     * capture works in every process either way.
     */
    fun shouldSkipMainInit(context: Context): Boolean {
        val name = currentProcessName(context)
        if (name == context.packageName) return false
        Log.i("MinisApp", "non-main process ($name): skipping main-process init — ACRA already armed")
        return true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun frameworkProcessName(): String? =
        runCatching { Application.getProcessName() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun cmdlineProcessName(): String? =
        runCatching { parseProcessName(File("/proc/self/cmdline").readBytes()) }.getOrNull()

    private fun activityManagerProcessName(context: Context): String? = runCatching {
        val pid = Process.myPid()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }.getOrNull()
}

/**
 * First NUL-separated token of a Linux `/proc/<pid>/cmdline` blob — the
 * process name Android's zygote wrote when it forked us. Pure so the JVM
 * suite can pin the parsing rules; see ProcessChecksTest.
 */
internal fun parseProcessName(cmdline: ByteArray): String? {
    if (cmdline.isEmpty()) return null
    var end = 0
    while (end < cmdline.size && cmdline[end] != 0.toByte()) end++
    if (end == 0) return null
    return String(cmdline, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
}
