package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyPermissionPolicyTest {
    @Test fun `global ask is the default and caps per-tool bypass`() {
        assertEquals(
            OffloadPermissionManager.PermissionLevel.ASK_ONCE,
            PrivacyPermissionPolicy.effective(
                "calendar",
                OffloadPermissionManager.PermissionLevel.ASK_ONCE,
                OffloadPermissionManager.PermissionLevel.BYPASS,
            ),
        )
    }

    @Test fun `global deny cannot be weakened by tool override`() {
        assertEquals(
            OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
            PrivacyPermissionPolicy.effective(
                "photos",
                OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
                OffloadPermissionManager.PermissionLevel.BYPASS,
            ),
        )
    }

    @Test fun `per-tool deny tightens global allow`() {
        assertEquals(
            OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
            PrivacyPermissionPolicy.effective(
                "contacts",
                OffloadPermissionManager.PermissionLevel.BYPASS,
                OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
            ),
        )
    }
}
