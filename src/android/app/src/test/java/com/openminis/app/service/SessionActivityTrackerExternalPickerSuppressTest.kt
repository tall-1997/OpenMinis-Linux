package com.openminis.app.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-attach-picker-suppress] Contract of the generic external-picker
 * suppress gate (issue #215).
 *
 * The floating capsule hides on any bg-only event (#451), so handing the
 * screen to a system picker (camera / photo-video picker / document picker)
 * while a tool runs would pop the capsule on top of the picker. The camera
 * path has carried a suppress flag since #451; the photo/file entries never
 * did — fullscreen (Minis truly backgrounded) broke, multi-window did not.
 * The gate is now external-picker-generic and this test pins the StateFlow
 * semantics the service combines on.
 */
class SessionActivityTrackerExternalPickerSuppressTest {

    @After
    fun tearDown() {
        SessionActivityTracker.setExternalPickerSuppressActive(false)
    }

    @Test
    fun `gate defaults to released`() {
        assertFalse(SessionActivityTracker.externalPickerSuppressActive.value)
    }

    @Test
    fun `gate can be set before an external picker launch`() {
        SessionActivityTracker.setExternalPickerSuppressActive(true)
        assertTrue(SessionActivityTracker.externalPickerSuppressActive.value)
    }

    @Test
    fun `gate releases in the picker result callback`() {
        SessionActivityTracker.setExternalPickerSuppressActive(true)
        SessionActivityTracker.setExternalPickerSuppressActive(false)
        assertFalse(SessionActivityTracker.externalPickerSuppressActive.value)
    }

    @Test
    fun `gate writes are idempotent`() {
        SessionActivityTracker.setExternalPickerSuppressActive(true)
        SessionActivityTracker.setExternalPickerSuppressActive(true)
        assertTrue(SessionActivityTracker.externalPickerSuppressActive.value)
        SessionActivityTracker.setExternalPickerSuppressActive(false)
        SessionActivityTracker.setExternalPickerSuppressActive(false)
        assertFalse(SessionActivityTracker.externalPickerSuppressActive.value)
    }
}
