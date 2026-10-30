package com.openminis.app.ui.settings

import com.openminis.app.plugins.builtin.BuiltinPluginStore
import com.openminis.app.plugins.builtin.GatewayServiceManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the local gateway page's state ladder.
 *
 * The page has two independent inputs — the on-disk install marker and the
 * live service state — and the ordering between them is the whole point:
 * a FAILED install must never render as a runnable gateway just because a
 * stale service state says RUNNING, and an INSTALLING marker must win over
 * anything the service reports (a fresh install has no service yet).
 *
 * Robolectric-free: the ladder is a pure function precisely so this test can
 * exist without a Context.
 */
class LocalGatewayScreenTest {

    private fun ui(
        status: BuiltinPluginStore.Status,
        service: GatewayServiceManager.ServiceState,
    ) = localGatewayUiState(status, service)

    @Test
    fun `not installed wins over any service state`() {
        GatewayServiceManager.ServiceState.entries.forEach { service ->
            assertEquals(
                "service=$service",
                LocalGatewayUiState.NOT_INSTALLED,
                ui(BuiltinPluginStore.Status.NOT_INSTALLED, service),
            )
        }
    }

    @Test
    fun `installing and failed outrank the service state`() {
        assertEquals(
            LocalGatewayUiState.INSTALLING,
            ui(BuiltinPluginStore.Status.INSTALLING, GatewayServiceManager.ServiceState.RUNNING),
        )
        assertEquals(
            LocalGatewayUiState.FAILED,
            ui(BuiltinPluginStore.Status.FAILED, GatewayServiceManager.ServiceState.RUNNING),
        )
    }

    @Test
    fun `installed maps the service states one to one`() {
        assertEquals(
            LocalGatewayUiState.RUNNING,
            ui(BuiltinPluginStore.Status.INSTALLED, GatewayServiceManager.ServiceState.RUNNING),
        )
        assertEquals(
            LocalGatewayUiState.STARTING,
            ui(BuiltinPluginStore.Status.INSTALLED, GatewayServiceManager.ServiceState.STARTING),
        )
        assertEquals(
            LocalGatewayUiState.STOPPED,
            ui(BuiltinPluginStore.Status.INSTALLED, GatewayServiceManager.ServiceState.STOPPED),
        )
        // STOPPING has no UI state of its own: the switch is disabled by the
        // page while the transition runs, so it renders as the target state.
        assertEquals(
            LocalGatewayUiState.STOPPED,
            ui(BuiltinPluginStore.Status.INSTALLED, GatewayServiceManager.ServiceState.STOPPING),
        )
    }

    @Test
    fun `every state has a label`() {
        LocalGatewayUiState.entries.forEach { state ->
            assertEquals("state=$state", true, localGatewayLabelRes(state) != 0)
        }
    }
}
