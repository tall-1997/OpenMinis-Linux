package com.openminis.app.sandbox

import android.content.Context
import android.os.StatFs

/** Partition sample for [GuestWorkloadPolicy.diskRefusal]. */
internal object DiskPressure {
    fun refusal(context: Context, command: String): String? {
        val sample = runCatching { sample(context) }.getOrNull() ?: return null
        return com.openminis.app.sandbox.kernel.IoGovernor.admit(sample.first, sample.second, command)
    }

    private fun sample(context: Context): Pair<Long, Long> {
        val stat = StatFs(context.filesDir.absolutePath)
        val available = stat.availableBlocksLong * stat.blockSizeLong
        val total = stat.blockCountLong * stat.blockSizeLong
        return available to total
    }
}
