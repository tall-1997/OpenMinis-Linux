package com.openminis.app.sandbox.kernel

import com.openminis.app.sandbox.GuestWorkloadPolicy

/**
 * Global IO admission. Disk pressure is not a command denylist: anything that
 * is not an obvious read is refused when the volume is already the constraint.
 */
object IoGovernor {
    fun admit(availableBytes: Long, totalBytes: Long, command: String): String? =
        GuestWorkloadPolicy.diskRefusal(availableBytes, totalBytes, command)
}
