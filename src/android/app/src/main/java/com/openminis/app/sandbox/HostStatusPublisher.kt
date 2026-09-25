package com.openminis.app.sandbox

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes `/run/minis-host-status.json` inside the Ubuntu rootfs so guest
 * scripts can `cat` host extras (temperature, free storage, fg/bg, wakelock)
 * without walking `ubuntu-rootfs` and without a NativeOffload round-trip.
 *
 * Refreshed on host events plus a [INTERVAL_MS] heartbeat. Uses StatFs only — never recursive dirSize.
 */
object HostStatusPublisher {

    private const val TAG = "HostStatusPublisher"
    private const val INTERVAL_MS = 60_000L
    private const val RELATIVE_PATH = "run/minis-host-status.json"

    private val started = AtomicBoolean(false)
    private var job: Job? = null
    @Volatile private var currentRootfs: File? = null

    fun guestPath(): String = "/$RELATIVE_PATH"

    fun start(context: Context, rootfsDir: File) {
        currentRootfs = rootfsDir
        writeOnce(context, rootfsDir)
        HostEventHooks.init(context)
        HostEventBridge.start(context, rootfsDir)
        SandboxJobKeepAlive.writeResumeHint(context, rootfsDir)
        SandboxFirewall.syncHttpProxy(context)
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        job = (app as com.openminis.app.MinisApp).appCoroutineScopes.io.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                val r = currentRootfs ?: continue
                writeOnce(app, r)
            }
        }
    }

    /** Process-lifetime in production; used when the live rootfs is torn down. */
    fun stop() {
        job?.cancel()
        job = null
        started.set(false)
        currentRootfs = null
        HostEventBridge.stop()
    }

    fun writeOnce(context: Context, rootfsDir: File) {
        if (!rootfsDir.isDirectory) return
        val json = snapshot(context).toString()
        val runDir = File(rootfsDir, "run").also { it.mkdirs() }
        val target = File(runDir, "minis-host-status.json")
        val tmp = File(runDir, "minis-host-status.json.tmp")
        try {
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.writeText(json)
                tmp.delete()
            }
            val procJson = ProcfsSnapshot.snapshot().toString()
            val procTarget = File(runDir, "minis-proc.json")
            val procTmp = File(runDir, "minis-proc.json.tmp")
            procTmp.writeText(procJson)
            if (!procTmp.renameTo(procTarget)) {
                procTarget.writeText(procJson)
                procTmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
        }
    }

    fun snapshot(context: Context): JSONObject {
        val json = JSONObject()
        json.put("timestamp_ms", System.currentTimeMillis())
        json.put("app_foreground", (context.applicationContext as? MinisApp)?.isAppForeground() == true)
        json.put("wakelock_held", AgentForegroundService.wakeLockHeld)
        json.put("offload_handlers", JSONArray(NativeOffloadServer.registeredHandlers.sorted()))

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) (level * 100) / scale else JSONObject.NULL
            json.put("battery_percent", pct)
            val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE) {
                val c = tenths / 10.0
                if (c in -20.0..80.0) json.put("temperature_celsius", c)
                else json.put("temperature_celsius_raw", tenths)
            }
        }

        try {
            val s = StatFs(Environment.getDataDirectory().path)
            json.put("storage_free_bytes", s.availableBytes)
            json.put("storage_total_bytes", s.totalBytes)
        } catch (t: Throwable) {
            json.put("storage_error", t.message ?: "StatFs unavailable")
        }
        json.put("event_log", "/run/android-events.jsonl")
        json.put("http_proxy", SandboxHttpProxy.envBlock()?.get("http_proxy") ?: JSONObject.NULL)
        json.put("firewall", SandboxFirewall.snapshot(context))
        json.put("doze", DozeSnapshot.json(context))
        val proc = ProcfsSnapshot.snapshot()
        json.put(
            "proc",
            JSONObject()
                .put("self_pid", proc.opt("self_pid"))
                .put("self_uid", proc.opt("self_uid"))
                .put("readable_count", proc.opt("readable_count"))
                .put("hidepid", proc.opt("hidepid")),
        )
        return json
    }
}
