package com.openminis.app.sandbox

import kotlinx.coroutines.sync.Mutex

/**
 * Cross-session locks so parallel sub-agents / chats cannot deadlock the
 * shared PRoot guest (one apt dpkg lock).
 *
 * Ultra port note: fork also carried an execution admission gate here
 * (AdaptiveExecutionGate + GuestWorkloadPolicy + ToolLimitPrefs), which is
 * part of the sandbox.kernel stack that was not ported; only the shared
 * apt mutex has callers on the 1.15 base.
 */
object SandboxResourceGate {

    /**
     * Shared with [RootfsManager] so boot-time `minis-mirror` / dpkg-world
     * restore cannot race an agent `apt-get` on the guest dpkg lock files.
     */
    val aptMutex = Mutex()
}
