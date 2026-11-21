package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.MinisApp
import com.openminis.app.config.InMemorySource
import com.openminis.app.config.Prefs
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.MultiAgentSettingsRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.awaitPersisted
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.ProviderHeaderStore
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplication

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MinisApp::class, shadows = [SessionOpenProjectionTest.ShadowMinisApp::class])
class SessionOpenProjectionTest {
    @Implements(MinisApp::class)
    class ShadowMinisApp : ShadowApplication() {
        @Implementation
        fun onCreate() = Unit
    }

    private val app = ApplicationProvider.getApplicationContext<MinisApp>()
    private val context get() = app
    private lateinit var db: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var providerRepository: ProviderRepository
    private val sessionId = "session-open-anthropic-api-key"
    private val instanceId = "session-open-anthropic-api-key-fixture"
    private var entryId: String = ""

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Prefs.install(InMemorySource())
        // [T-ci-test-hang] Robolectric ships no AndroidKeyStore provider, so a
        // real EncryptedSharedPreferences.create blocks forever in Tink's
        // keystore lookup instead of throwing (run 44929803764 hung this exact
        // test for 13.5 min). The fixture below only needs the store to hold an
        // API key for the duration of one test, so back it with the platform
        // SharedPreferences that Robolectric does implement.
        EncryptedPrefsFactory.installOpsForTest { ctx -> PlainPrefsOps(ctx) }
        app.appContainer.multiAgentSettingsRepository = MultiAgentSettingsRepository(app)
        ProviderHeaderStore.resetForTest()
        EncryptedPrefsFactory.resetCacheForTest()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = context.filesDir,
            db = db,
        )
        providerRepository = ProviderRepository(context)
        providerRepository.awaitConfigLoaded()

        val instance = ProviderInstance(
            id = instanceId,
            label = "Anthropic API key fixture",
            providerType = ProviderType.anthropic,
            credentialType = ProviderCredential.apiKey,
        )
        assertTrue(providerRepository.addInstance(instance))
        providerRepository.saveApiKey(instanceId, "test-api-key")
        providerRepository.awaitPersisted()
        entryId = providerRepository.entriesFor(instanceId)
            .first { it.model.id == LLMModel.claudeHaiku45.id }
            .id
        ProviderFactory.invalidateInstance(instanceId)

        db.chatDao().insertSession(
            ChatSessionEntity(
                id = sessionId,
                title = "API-key Anthropic session",
                modelId = LLMModel.claudeHaiku45.id,
                createdAt = 1L,
                updatedAt = 1L,
                modelBinding = """{"type":"entry","entryId":"$entryId"}""",
            ),
        )
    }

    @After
    fun tearDown() = runBlocking {
        db.close()
        if (::providerRepository.isInitialized) {
            providerRepository.removeInstance(instanceId)
            providerRepository.deleteApiKey(instanceId)
            providerRepository.awaitPersisted()
        }
        ProviderFactory.invalidateInstance(instanceId)
        ProviderHeaderStore.resetForTest()
        EncryptedPrefsFactory.resetCacheForTest()
        app.appContainer.multiAgentSettingsRepository = null
        Dispatchers.resetMain()
    }

    @Test
    fun openingApiKeyAnthropicSessionCompletesTheRealContextEstimate() = runBlocking {
        val viewModel = ChatViewModel(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            context = context,
            memoryRepository = MemoryRepository(context.filesDir),
        )
        val viewModelStore = ViewModelStore().apply { put("session-open", viewModel) }
        try {
            withTimeout(15_000) { viewModel.sessionLoaded.first { it } }
            val usage = withTimeout(15_000) {
                viewModel.contextUsage.first { it.usedTokens > 0L && it.windowTokens > 0L }
            }

            assertEquals(entryId, viewModel._activeEntryId.value)
            val provider = viewModel.currentProvider as? AnthropicProvider
            assertNotNull("The persisted entry binding must resolve to Anthropic", provider)
            assertFalse("The fixture must exercise the API-key path", provider!!.isOAuth)
            assertTrue("The request projection must publish a positive estimate", usage.usedTokens > 0L)
            assertTrue("The model context window must be published", usage.windowTokens > 0L)
        } finally {
            viewModelStore.clear()
        }
    }

    /**
     * [T-ci-test-hang] [EncryptedPrefsFactory.Ops] backed by the plain
     * SharedPreferences Robolectric does implement. The real [EncryptedPrefsFactory.AndroidOps]
     * needs a working AndroidKeyStore, which Robolectric does not provide, and
     * the failure mode there is a hang, not an exception.
     *
     * The encryption itself is not under test here: the fixture asserts that an
     * API-key Anthropic session publishes a real context estimate, and it only
     * needs the key to survive the write→read round trip.
     */
    private class PlainPrefsOps(private val context: android.content.Context) :
        EncryptedPrefsFactory.Ops {
        override fun build(fileName: String): android.content.SharedPreferences =
            context.getSharedPreferences("test-plain-$fileName", android.content.Context.MODE_PRIVATE)

        override fun masterKeyWorks(): Boolean = true

        override fun discardStore(fileName: String) {
            context.deleteSharedPreferences("test-plain-$fileName")
        }

        override fun deleteMasterKey() = Unit

        override fun inMemory(): android.content.SharedPreferences = build("in-memory")

        override fun log(priority: Int, msg: String, t: Throwable?) = Unit
    }
}
