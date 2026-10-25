package com.openminis.app.sandbox

import android.content.Context
import android.os.PowerManager
import com.openminis.app.power.PowerOptimizationManager
import org.json.JSONObject

object DozeSnapshot {
    fun json(context: Context): JSONObject {
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return JSONObject()
            .put("ignoring_battery_optimizations", PowerOptimizationManager.isIgnoringBatteryOptimizations(app))
            .put("device_idle", pm?.isDeviceIdleMode == true)
            .put("power_save", pm?.isPowerSaveMode == true)
            .put("interactive", pm?.isInteractive == true)
            .put("oem_autostart_guidance", PowerOptimizationManager.needsOemAutostartGuidance())
            .put("vendor", PowerOptimizationManager.Vendor.current().name)
    }
}
