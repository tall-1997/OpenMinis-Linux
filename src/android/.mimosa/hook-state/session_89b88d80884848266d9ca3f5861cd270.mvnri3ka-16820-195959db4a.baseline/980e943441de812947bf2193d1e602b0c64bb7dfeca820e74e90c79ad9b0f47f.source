package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-apt-ro-exempt] The read-only apt/dpkg whitelist that bypasses the apt
 * mutex. Bias: a false TRUE risks concurrent package-state writers; a false
 * FALSE only costs a queue wait. Every keep-locked case below exists because
 * a looser pattern would have admitted it.
 */
class SandboxResourceGateReadOnlyTest {

    @Test
    fun `read-only queries are exempt`() {
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt list --installed"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt show curl"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt search nginx"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt policy curl"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("/usr/bin/apt list --installed"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt-cache policy git"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt-cache show python3"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("dpkg -l"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("dpkg -s curl"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("dpkg --listfiles curl"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("dpkg -S libpython3.so"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt-get -s install python3"))
        assertTrue(SandboxResourceGate.isReadOnlyPackageManager("apt-get --simulate install curl"))
    }

    @Test
    fun `mutating package commands stay locked`() {
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt-get install python3"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt update"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt upgrade -y"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt remove curl"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("dpkg -i pkg.deb"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("dpkg --configure -a"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt-get download curl")) // writes ./.deb
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt-cache gencaches")) // rebuilds cache
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("sdkmanager --install ndk"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("minis-dev-setup-full"))
    }

    @Test
    fun `compound and redirecting forms stay locked`() {
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt list --installed; apt-get install x"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt list | head"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt list > /tmp/out"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("dpkg -l `which curl`"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt show $(cat /tmp/p)"))
    }

    @Test
    fun `value-taking config flags stay locked`() {
        // -o APT::Dir::State=/elsewhere could redirect the dpkg database.
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("apt-get -o APT::Sandbox=1 list"))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("dpkg --admindir=/tmp/x -l"))
    }

    @Test
    fun `blank input is not exempt`() {
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager(""))
        assertFalse(SandboxResourceGate.isReadOnlyPackageManager("   "))
    }
}
