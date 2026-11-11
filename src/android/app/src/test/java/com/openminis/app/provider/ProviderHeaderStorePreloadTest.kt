package com.openminis.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [GH#221] / [OpenMinis#221] The first `headersFor` must not open the
 * Keystore-backed EncryptedSharedPreferences on the caller's thread.
 *
 * `ProviderFactory.create` is reached from `ChatViewModelLoadExt`'s
 * `viewModelScope` (Main), so a synchronous first load there is a StrictMode
 * disk/Keystore read on the main thread during chat open. The store is now
 * preloaded from `MinisApp.onCreate`'s IO coroutine; until that lands,
 * `headersFor` answers with an empty map instead of triggering the load.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderHeaderStorePreloadTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProviderHeaderStore.resetForTest()
    }

    @After
    fun tearDown() {
        ProviderHeaderStore.resetForTest()
    }

    @Test
    fun `headersFor before preload returns empty without loading`() {
        ProviderHeaderStore.prime(context)

        assertEquals(emptyMap<String, String>(), ProviderHeaderStore.headersFor("instance-1"))
        assertFalse("must not synchronously open the keystore", ProviderHeaderStore.isLoadedForTest())
        assertFalse(ProviderHeaderStore.openCodeSessionEnabled("instance-1"))
        assertEquals(emptySet<String>(), ProviderHeaderStore.configuredInstanceIds())
    }

    @Test
    fun `preload loads once and bumps the revision`() {
        ProviderHeaderStore.prime(context)
        val before = ProviderHeaderStore.revision()

        ProviderHeaderStore.preload()

        assertTrue(ProviderHeaderStore.isLoadedForTest())
        assertTrue(ProviderHeaderStore.revision() > before)
        // A second call is a no-op (startup warm-up may race a retry).
        val after = ProviderHeaderStore.revision()
        ProviderHeaderStore.preload()
        assertEquals(after, ProviderHeaderStore.revision())
    }
}
