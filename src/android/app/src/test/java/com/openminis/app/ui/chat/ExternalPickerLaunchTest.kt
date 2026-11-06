package com.openminis.app.ui.chat

import android.os.Looper
import com.openminis.app.service.SessionActivityTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [T-android-attach-picker-suppress] Behaviour contract of the single
 * external-picker handoff helper (issue #215): the overlay-suppress gate is
 * set BEFORE the picker launch, the launch itself is deferred past the menu
 * Popup's teardown, and a launch failure releases the gate because no result
 * callback will ever fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExternalPickerLaunchTest {

    @After
    fun tearDown() {
        SessionActivityTracker.setExternalPickerSuppressActive(false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `gate is set before the deferred launch runs`() {
        val order = mutableListOf<String>()
        launchExternalPickerAfterMenuDismiss {
            order.add("launch:gate=${SessionActivityTracker.externalPickerSuppressActive.value}")
        }
        order.add("after-call:gate=${SessionActivityTracker.externalPickerSuppressActive.value}")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("after-call:gate=true", "launch:gate=true"), order)
    }

    @Test
    fun `launch is deferred past menu dismissal`() {
        var ran = false
        launchExternalPickerAfterMenuDismiss { ran = true }
        // Nothing has run yet: the helper hands off on a later looper turn.
        assertFalse(ran)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ran)
    }

    @Test
    fun `launch failure releases the gate`() {
        launchExternalPickerAfterMenuDismiss { error("no activity handles the picker") }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(SessionActivityTracker.externalPickerSuppressActive.value)
    }

    @Test
    fun `successful launch keeps the gate set for the result callback`() {
        launchExternalPickerAfterMenuDismiss { /* picker opens; callback clears later */ }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(SessionActivityTracker.externalPickerSuppressActive.value)
    }
}
