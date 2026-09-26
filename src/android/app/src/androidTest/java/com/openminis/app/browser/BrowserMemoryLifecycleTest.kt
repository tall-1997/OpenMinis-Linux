package com.openminis.app.browser

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class BrowserMemoryLifecycleTest {
    @Test fun closeAndOwnerDisposalDestroyWebViewsIdempotently() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = BrowserTabPool(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                val tab = checkNotNull(pool.ensureTabForUI())
                pool.closeTabFromUI(tab.id)
                assertTrue(tab.manager.isDisposed)
                assertTrue(pool.tabs.value.isEmpty())
                val next = checkNotNull(pool.ensureTabForUI())
                pool.dispose()
                pool.dispose()
                assertTrue(next.manager.isDisposed)
                assertTrue(pool.tabs.value.isEmpty())
                assertNull(pool.ensureTabForUI())
            } finally {
                pool.dispose()
            }
        }
    }

    @Test fun pressureTrimProtectsVisibleAndBusyTabs() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = BrowserTabPool(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                val tab = checkNotNull(pool.ensureTabForUI())
                pool.isVisible = true
                pool.trimIdleTabs()
                assertFalse(tab.manager.isDisposed)
                pool.isVisible = false
                tab.inUse = true
                pool.trimIdleTabs()
                assertFalse(tab.manager.isDisposed)
                tab.inUse = false
                pool.trimIdleTabs()
                assertTrue(tab.manager.isDisposed)
            } finally {
                pool.dispose()
            }
        }
    }

    @Test fun globalLimitCannotDestroyVisiblePages() = runBlocking {
        withContext(Dispatchers.Main) {
            val pools = List(7) { BrowserTabPool(InstrumentationRegistry.getInstrumentation().targetContext) }
            try {
                pools.take(6).forEach { pool ->
                    pool.isVisible = true
                    assertNotNull(pool.ensureTabForUI())
                }
                assertNull(pools.last().ensureTabForUI())
                val first = checkNotNull(pools.first().activeManager)
                pools.first().isVisible = false
                assertNotNull(pools.last().ensureTabForUI())
                assertTrue(first.isDisposed)
            } finally {
                pools.forEach { it.dispose() }
            }
        }
    }
}
