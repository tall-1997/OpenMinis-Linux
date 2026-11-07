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

    // ── [T-android-vscreen-tier] public displays (shell-uid owner) ────────

    /**
     * System context for the display service, created once. This process has
     * no Application; `ActivityThread.systemMain()` hands out the system
     * context the same way the app_process hosts (vdisplay-android mode) get
     * one. The context is then RE-PACKAGED to com.android.shell:
     * DisplayManagerService validates that the caller's package name matches
     * its uid, and the system context's package ("android") does not match
     * uid 2000 — the shell package does, which is what makes the owner
     * branch of `am start --display` accept app launches on our display.
     */
    private val shellDisplayContext: android.content.Context? by lazy {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getMethod("systemMain").invoke(null)
            @Suppress("DEPRECATION")
            val sys = atClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
            sys.createPackageContext(
                "com.android.shell",
                android.content.Context.CONTEXT_INCLUDE_CODE or
                    android.content.Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrNull()
    }

    /** Displays this service created; [destroyPublicDisplay] releases them. */
    private val ownedDisplays = HashMap<Int, android.hardware.display.VirtualDisplay>()

    override fun createPublicDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: android.view.Surface?,
    ): Int = runCatching {
        val ctx = shellDisplayContext ?: return -1
        val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        val vd = dm.createVirtualDisplay(
            name, width, height, dpi, surface,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            null, null,
        ) ?: return -1
        val id = vd.display.displayId
        synchronized(ownedDisplays) { ownedDisplays[id] = vd }
        id
    }.getOrDefault(-1)

    override fun destroyPublicDisplay(displayId: Int): Boolean {
        val vd = synchronized(ownedDisplays) { ownedDisplays.remove(displayId) } ?: return false
        return runCatching { vd.release() }.isSuccess
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
        const val VERSION = 2

        private const val MAX_TASKS = 100

        /** IActivityTaskManager/IActivityManager move verbs, in capability order. */
        private val MOVE_METHODS = setOf("moveRootTaskToDisplay", "moveTaskToDisplay", "moveStackToDisplay")
    }
}
