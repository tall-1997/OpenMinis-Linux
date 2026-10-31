package com.openminis.app.vscreen

import android.os.Build
import kotlin.system.exitProcess

/**
 * [T-android-vscreen2] Shizuku user service implementation: instantiated by
 * Shizuku in a process running as the shell user (uid 2000), which may call
 * the hidden IActivityTaskManager / IWindowManager binder APIs that the
 * Shizuku shell one-liners cannot reach reliably (task listing across
 * displays, task moves, per-display IME policy).
 *
 * Everything version-sensitive goes through reflection and fails soft — a
 * method this ROM does not expose makes the single call return false/"" so
 * the client can degrade to the shell probe table instead of guessing.
 *
 * Runs with NO app context: binder-level reflection only. Do not add code
 * here that needs an Application or UI thread; bump [VERSION] (client side)
 * whenever this class changes so Shizuku restarts the service process.
 */
class VscreenUserService : IVscreenService.Stub() {

    // ── IActivityTaskManager / IActivityManager service handle ───────────

    /**
     * The activity/task binder service, whichever class this Android has:
     * android.app.ActivityTaskManager.getService() (API 28+) or the older
     * android.app.ActivityManager.getService() (API 26/27).
     */
    private val taskService: Any by lazy {
        val service = runCatching {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        }.getOrNull()
        service ?: Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            ?: error("neither ActivityTaskManager nor ActivityManager service reachable")
    }

    /** All tasks the shell uid can see, parsed into [Row]s (empty when unreachable). */
    private fun allTasks(): List<Row> = runCatching {
        // Prefer the overload with the most parameters; the second int of the
        // 33+ variant is a display filter (INVALID_DISPLAY = all).
        val m = taskService.javaClass.methods
            .filter { it.name == "getTasks" && it.parameterTypes.all { p -> p == Int::class.javaPrimitiveType } }
            .maxByOrNull { it.parameterTypes.size } ?: return emptyList()
        var ints = 0
        val args = m.parameterTypes.map { if (ints++ == 0) MAX_TASKS else -1 }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        val raw = m.invoke(taskService, *args) as? List<Any> ?: return emptyList()
        raw.mapNotNull(::parseRow)
    }.getOrDefault(emptyList())

    private class Row(val taskId: Int, val displayId: Int, val packageName: String, val topActivity: String)

    /** TaskInfo (31+) / RunningTaskInfo (26-30) → Row; StackInfo-like objects without a taskId field are skipped. */
    private fun parseRow(t: Any): Row? {
        val taskId = field(t, "taskId")?.toString()?.toIntOrNull() ?: return null
        val display = field(t, "displayId")?.toString()?.toIntOrNull() ?: 0
        val top = field(t, "topActivity") as? android.content.ComponentName
            ?: (field(t, "baseIntent") as? android.content.Intent)?.component
        val pkg = top?.packageName
            ?: (field(t, "baseActivity") as? android.content.ComponentName)?.packageName
            ?: ""
        return Row(taskId, display, pkg, top?.className ?: "")
    }

    private fun field(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val v = runCatching { c.getDeclaredField(name).apply { isAccessible = true }.get(target) }.getOrNull()
            if (v != null) return v
            c = c.superclass
        }
        return null
    }

    // ── IVscreenService ──────────────────────────────────────────────────

    override fun listTopTask(displayId: Int): String =
        allTasks().firstOrNull { it.displayId == displayId }?.wire() ?: ""

    override fun listTasks(): String =
        allTasks().joinToString("\n") { it.wireAll() }

    override fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        val method = taskService.javaClass.methods.firstOrNull {
            it.name in MOVE_METHODS && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        }
        if (method != null) {
            return runCatching { method.invoke(taskService, taskId, displayId) }.isSuccess
        }
        // Nothing matched by name+signature; try name-only (some ROMs reorder params).
        val loose = taskService.javaClass.methods.firstOrNull { it.name in MOVE_METHODS && it.parameterTypes.size == 2 }
            ?: return false
        return runCatching { loose.invoke(taskService, taskId, displayId) }.isSuccess
    }

    override fun setDisplayImePolicy(displayId: Int, policy: Int): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, policy)
            true
        }.getOrDefault(false)
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun Row.wire(): String = "$taskId\t$packageName\t$topActivity"
    private fun Row.wireAll(): String = "$taskId\t$displayId\t$packageName\t$topActivity"

    companion object {
        /**
         * Bump whenever [VscreenUserService] changes so Shizuku restarts the
         * service process with the new code (client passes it in
         * Shizuku.UserServiceArgs.version).
         */
        const val VERSION = 1

        private const val MAX_TASKS = 100

        /** IActivityTaskManager/IActivityManager move verbs, in capability order. */
        private val MOVE_METHODS = setOf("moveRootTaskToDisplay", "moveTaskToDisplay", "moveStackToDisplay")
    }
}
