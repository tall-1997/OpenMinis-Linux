package com.openminis.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Optional OEM packages are absent on most devices. A missing one is a miss,
 * never a startup exception.
 */
object OptionalPackages {
    private const val TAG = "OptionalPackages"
    private val NAMES = listOf(
        "com.lgsi.iconpack.overlayable",
    )

    fun probe(context: Context) {
        val pm = context.packageManager
        for (name in NAMES) {
            val present = try {
                pm.getPackageInfo(name, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (t: Throwable) {
                Log.w(TAG, "optional package $name unavailable: ${t.javaClass.simpleName}")
                false
            }
            if (!present) Log.d(TAG, "optional package absent: $name")
        }
    }
}
