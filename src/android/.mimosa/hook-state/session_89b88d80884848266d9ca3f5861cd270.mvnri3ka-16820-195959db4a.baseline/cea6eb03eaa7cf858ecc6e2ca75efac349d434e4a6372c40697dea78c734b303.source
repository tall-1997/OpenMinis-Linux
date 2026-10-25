package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiHostTest {
    @Test fun pixelAndAospKeepLiveTemplates() {
        assertTrue(SystemUiHost.allowsLiveNotificationTemplates(
            SystemUiHost.Signals("Google", "google", "AP4A", "google/oriole/oriole:16/AP4A/1:user/release-keys"),
        ))
        assertTrue(SystemUiHost.allowsLiveNotificationTemplates(
            SystemUiHost.Signals("Android", "generic", "sdk", "generic/sdk_gphone64_arm64/emu:16/1:userdebug/dev-keys"),
        ))
    }

    @Test fun hyperOsAndZuiRejectLiveTemplates() {
        assertFalse(SystemUiHost.allowsLiveNotificationTemplates(
            SystemUiHost.Signals("Xiaomi", "Redmi", "OS3.0.12.0", "Xiaomi/myron/myron:16/UKQ1/1:user/release-keys"),
        ))
        assertFalse(SystemUiHost.allowsLiveNotificationTemplates(
            SystemUiHost.Signals("Lenovo", "Lenovo", "ZUI 17.0.045", "Lenovo/TB321FU/TB321FU:16/ZUI/1:user/release-keys"),
        ))
        assertFalse(SystemUiHost.allowsLiveNotificationTemplates(
            SystemUiHost.Signals("samsung", "samsung", "One UI 8", "samsung/e3q/e3q:16/1:user/release-keys"),
        ))
        assertFalse(SystemUiHost.allowsLiveNotificationTemplates(
            SystemUiHost.Signals("OPPO", "OPPO", "ColorOS", "OPPO/PJZ110/PJZ110:16/1:user/release-keys"),
        ))
    }
}
