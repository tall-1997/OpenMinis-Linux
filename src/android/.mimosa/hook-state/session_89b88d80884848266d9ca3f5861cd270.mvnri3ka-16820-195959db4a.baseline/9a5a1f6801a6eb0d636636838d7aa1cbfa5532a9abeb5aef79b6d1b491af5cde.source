package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildEnvironmentProfileTest {
    @Test fun `profiles expose fixed package sets`() {
        assertTrue(BuildEnvironmentProfile.NATIVE.packages.contains("build-essential"))
        assertTrue(BuildEnvironmentProfile.PYTHON.packages.contains("python3-venv"))
        assertEquals(BuildEnvironmentProfile.GO, BuildEnvironmentProfile.fromId("go"))
        assertEquals("minis-build-env rust plan", BuildEnvironmentProfile.RUST.setupCommand())
        assertEquals("minis-build-env android install", BuildEnvironmentProfile.ANDROID.setupCommand("install"))
    }

    @Test fun `unknown profile cannot be resolved`() {
        assertEquals(null, BuildEnvironmentProfile.fromId("arbitrary-package-name"))
    }
}
