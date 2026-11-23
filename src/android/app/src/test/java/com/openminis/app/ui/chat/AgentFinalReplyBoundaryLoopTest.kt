package com.openminis.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.MinisApp
import com.openminis.app.config.InMemorySource
import com.openminis.app.config.Prefs
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.MultiAgentSettingsRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderHeaderStore
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.service.ActiveRunContext
import com.openminis.app.service.ActiveRunRegistry
import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.ListDirTool
import com.openminis.app.tools.SessionFileReadCaches
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MinisApp::class, shadows = [SessionOpenProjectionTest.ShadowMinisApp::class])
class AgentFinalReplyBoundaryLoopTest {
    private val app = ApplicationProvider.getApplicationContext<MinisApp>()
    private val viewModels = ViewModelStore()
    private lateinit var db: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var providerRepository: ProviderRepository

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Prefs.install(InMemorySource())
        // As in SessionOpenProjectionTest, reset before installing the AndroidKeyStore stand-in.
        EncryptedPrefsFactory.resetCacheForTest()
        EncryptedPrefsFactory.installOpsForTest { PlainPrefsOps(it) }
        ProviderHeaderStore.resetForTest()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(db.chatDao(), db.goalDao(), filesDir = app.filesDir, db = db)
        providerRepository = ProviderRepository(app)
        app.appContainer.apply {
            database = db
            chatRepository = this@AgentFinalReplyBoundaryLoopTest.chatRepository
            providerRepository = this@AgentFinalReplyBoundaryLoopTest.providerRepository
            multiAgentSettingsRepository = MultiAgentSettingsRepository(app)
        }
        withTimeout(TIMEOUT_MS) { providerRepository.awaitConfigLoaded() }
        db.chatDao().insertSession(
            ChatSessionEntity(
                id = SESSION_ID,
                title = "Workspace inspection fixture",
                modelId = LLMModel.claudeHaiku45.id,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        chatRepository.appendMessage(
            SESSION_ID, "user",
            JSONArray().put(JSONObject().put("type", "text").put("value", USER_PROMPT)).toString(),
        )
        Unit
    }

    @After
    fun tearDown() {
        viewModels.clear()
        SessionFileReadCaches.clear(SESSION_ID)
        app.appContainer.apply {
            database = null
            chatRepository = null
            providerRepository = null
            multiAgentSettingsRepository = null
        }
        if (::db.isInitialized) db.close()
        // The provider.db singleton above binds to THIS test's filesDir; drop it
        // so later classes in the same Robolectric sandbox get a fresh handle.
        com.openminis.app.data.db.ProviderDatabase.resetForTest()
        ProviderHeaderStore.resetForTest()
        EncryptedPrefsFactory.resetCacheForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun toolRoundProseIsVisibleAsReply() = runBlocking {
        val viewModel = openViewModel("live")
        val workspace = SessionWorkspace.hostDir(app.filesDir, SESSION_ID, "workspace")
        workspace.mkdirs()
        val note = File(workspace, "note.txt").apply { writeText("The sample color is amber.") }
        val rounds = listOf(
            ToolRound("read-note", FileReadTool.NAME, "$WORKSPACE/${note.name}",
                "I will read the note to check the sample color.", note.readText()),
            ToolRound("list-workspace", ListDirTool.NAME, WORKSPACE,
                "I will also check which files are present.", "f ${note.name} ${note.length()}"),
        )
        viewModel.setToolAllowlist(rounds.map { it.name }.toSet())
        val beforeExecution = mutableMapOf<String, AssistantBody>()
        var duringSecondRequest: AssistantBody? = null
        val provider = ScriptedProvider { requestNumber ->
            if (requestNumber == 2) duringSecondRequest = viewModel.visibleAssistantBody()
            val round = rounds.getOrNull(requestNumber - 1)
            if (round != null) {
                emit(LLMStreamChunk.Text(round.progress))
                emit(LLMStreamChunk.ToolUseStart(round.id, round.name))
                emit(LLMStreamChunk.ToolCallComplete(round.id, round.name, round.args()))
                // Wait for the real streaming projection to include the tool. The loop cannot
                // dispatch it until this provider stream finishes.
                withTimeout(TIMEOUT_MS) {
                    viewModel.streamingById.first { byId ->
                        byId.values.any { delta -> delta.toolBlocks.any { it.id == round.id } }
                    }
                }
                beforeExecution[round.id] = viewModel.visibleAssistantBody()
                emit(LLMStreamChunk.Finished("tool_calls"))
            } else {
                check(requestNumber == 3) { "Unexpected model request $requestNumber" }
                emit(LLMStreamChunk.Text(FINAL_REPLY))
                emit(LLMStreamChunk.Finished("stop"))
            }
        }

        runLoop(viewModel, provider)

        assertEquals("Two tool turns followed by a natural completion", 3, provider.requests.size)
        assertTrue("The projected request lost the user prompt", provider.requests.first().any { message ->
            message.role == LLMMessage.Role.USER && message.contentParts
                .filterIsInstance<AgentContentPart.Text>().any { USER_PROMPT in it.text }
        })
        assertToolRoundInRequest(provider.requests[1], rounds[0])
        rounds.forEach { assertToolRoundInRequest(provider.requests[2], it) }

        val storedRows = chatRepository.loadSessionTail(SESSION_ID).messages
        val storedHistory = storedRows.map { viewModel.toLLMMessage(it) }
        rounds.forEach { assertToolRoundInRequest(storedHistory, it) }

        // [T-midturn-prose-is-reply] Prose is reply text: visible and CUMULATIVE
        // across tool rounds in the same assistant bubble — round 2 opens with
        // round 1's prose already there. This used to assert the opposite,
        // which is what produced "正文怎么还在卡片里".
        val proseSoFar = mutableListOf<String>()
        for (round in rounds) {
            proseSoFar += round.progress
            assertEquals(
                "Prose before ${round.name} dispatch must be visible",
                AssistantBody(proseSoFar.toList(), proseSoFar.toList()),
                beforeExecution[round.id],
            )
        }
        assertEquals(
            "Prose while waiting for the second response must be visible",
            AssistantBody(listOf(rounds[0].progress), listOf(rounds[0].progress)),
            duringSecondRequest,
        )

        val liveBody = viewModel.visibleAssistantBody()
        val expectedReply = rounds.map { it.progress } + FINAL_REPLY
        assertEquals(
            "All prose plus the final reply is visible. Live=${viewModel.messages.value} streaming=${viewModel.streamingById.value} " +
                "stored=${storedRows.map { it.partsJson }}",
            AssistantBody(expectedReply, expectedReply), liveBody,
        )

        val restored = viewModel.toChatMessages(storedRows)
        val reopened = openViewModel("reopened")
        assertEquals("Room rows reconstructed by toChatMessages", liveBody, assistantBody(restored))
        assertEquals("A reopened session shows the same reply", liveBody, reopened.visibleAssistantBody())
        for (messages in listOf(restored, reopened.messages.value)) {
            val blocks = messages.filter { it.role == "assistant" }.flatMap { it.toolBlocks }
            for (round in rounds) {
                assertTrue("Reopened process lost the ${round.name} prose", blocks.any { it.content == round.progress })
            }
        }
    }

    @Test
    fun naturalStopIsStillProgressWhenTheHostMustReconcileThePlan() = runBlocking {
        val viewModel = openViewModel("plan")
        val planTitle = "Verify the sample note"
        val progress = "The note is ready for verification."
        val plan = AgentPlanStore.add(SESSION_ID, null, planTitle)
        var beforeContinuation: AssistantBody? = null
        val provider = ScriptedProvider { requestNumber ->
            when (requestNumber) {
                1 -> emit(LLMStreamChunk.Text(progress))
                2 -> {
                    beforeContinuation = viewModel.visibleAssistantBody()
                    // Close the in-memory fixture board so this is the last response.
                    AgentPlanStore.update(SESSION_ID, null, plan.id, status = "done")
                    emit(LLMStreamChunk.Text(FINAL_REPLY))
                }
                else -> error("Unexpected model request $requestNumber")
            }
            emit(LLMStreamChunk.Finished("stop"))
        }

        try {
            runLoop(viewModel, provider)
            assertEquals("The plan board requires one more request after stop", 2, provider.requests.size)
            val continuation = provider.requests[1]
            assertTrue("The model needs its original text on continuation", continuation.any { message ->
                message.role == LLMMessage.Role.ASSISTANT && message.contentParts
                    .filterIsInstance<AgentContentPart.Text>().any { it.text == progress }
            })
            assertTrue("The continuation should carry the plan reminder", continuation.any { message ->
                message.role == LLMMessage.Role.USER && message.contentParts
                    .filterIsInstance<AgentContentPart.Text>().any { "Open items: $planTitle." in it.text }
            })
            // [T-midturn-prose-is-reply] `continueWith` commits the previous
            // attempt with `persistTextTurn`, so the prose is already durable and
            // the fresh bubble starts empty. What must hold is that the prose is
            // PERSISTED as reply text (not process text) and reaches the model on
            // the continuation — both asserted above — not that it lingers in the
            // new bubble.
            assertEquals(
                "A continuation starts a fresh bubble; the earlier prose is durable",
                AssistantBody(emptyList(), emptyList()),
                beforeContinuation,
            )
            val expectedReply = listOf(FINAL_REPLY)
            assertEquals(AssistantBody(expectedReply, expectedReply), viewModel.visibleAssistantBody())
        } finally {
            AgentPlanStore.clear(SESSION_ID, null)
        }
    }

    @Test
    fun plainChatStillShowsTheFirstTokensBeforeTheStreamFinishes() = runBlocking {
        val viewModel = openViewModel("plain")
        // An allowlist with no real tools models a chat that offers none.
        viewModel.setToolAllowlist(setOf("no_such_tool_for_this_fixture"))
        var duringStream: AssistantBody? = null
        val provider = ScriptedProvider { requestNumber ->
            assertEquals(1, requestNumber)
            emit(LLMStreamChunk.Text("The sample"))
            withTimeout(TIMEOUT_MS) {
                viewModel.streamingById.first { byId -> byId.values.any { it.content.isNotBlank() } }
            }
            duringStream = viewModel.visibleAssistantBody()
            emit(LLMStreamChunk.Text(" is amber."))
            emit(LLMStreamChunk.Finished("stop"))
        }

        runLoop(viewModel, provider)

        assertEquals("Plain chat keeps its live first tokens", AssistantBody(listOf("The sample"), listOf("The sample")), duringStream)
        val expected = listOf("The sample is amber.")
        assertEquals(AssistantBody(expected, expected), viewModel.visibleAssistantBody())
    }

    @Test
    fun reasonlessContinuationSettlesIntoOneReplyRowAfterReload() = runBlocking {
        val viewModel = openViewModel("reasonless")
        viewModel.setToolAllowlist(setOf("no_such_tool_for_this_fixture"))
        val provider = ScriptedProvider { requestNumber ->
            when (requestNumber) {
                1 -> emit(LLMStreamChunk.Text("The sample color"))
                2 -> {
                    emit(LLMStreamChunk.Text(" is amber, recorded in note.txt."))
                    emit(LLMStreamChunk.Finished("stop"))
                }
                else -> error("Unexpected model request $requestNumber")
            }
        }

        runLoop(viewModel, provider)

        assertEquals("One continuation request", 2, provider.requests.size)
        val reply = viewModel.visibleAssistantBody()
        assertEquals(
            "The cut-off prefix and its continuation are one answer",
            "The sample color is amber, recorded in note.txt.",
            reply.folded.joinToString(""),
        )
        assertEquals("The sample color is amber, recorded in note.txt.", reply.unfolded.joinToString(""))

        val storedRows = chatRepository.loadSessionTail(SESSION_ID).messages
        assertEquals("One durable assistant row for the answer", 1, storedRows.count { it.role == "assistant" })
        val reopened = openViewModel("reasonless-reopened")
        assertEquals(reply, reopened.visibleAssistantBody())
    }

    private suspend fun runLoop(viewModel: ChatViewModel, provider: LLMProvider) = withTimeout(TIMEOUT_MS) {
        // Like send/resume, claim the live projection before any row can be persisted.
        // Tail paging must not replace the active bubble with a partial DB projection.
        viewModel._isStreaming.value = true
        try {
            launchActiveRun(SESSION_ID, Dispatchers.Default, beforeStart = { viewModel.streamJob = it }) { run ->
                assertSame(run, ActiveRunContext.current())
                assertSame(run, ActiveRunRegistry.current(SESSION_ID))
                viewModel.runAgentLoop(provider, systemPrompt = null, skipInitialCompaction = true)
            }.join()
        } finally {
            viewModel._isStreaming.value = false
        }
    }

    private suspend fun openViewModel(key: String): ChatViewModel {
        val viewModel = ChatViewModel(SESSION_ID, chatRepository, providerRepository, app, MemoryRepository(app.filesDir))
        viewModels.put(key, viewModel)
        withTimeout(TIMEOUT_MS) { viewModel.sessionLoaded.first { it } }
        return viewModel
    }

    private fun ChatViewModel.visibleAssistantBody(): AssistantBody =
        assistantBody(mergeStreamingOverlay(messages.value, streamingById.value))

    private fun assistantBody(messages: List<ChatMessage>) = AssistantBody(
        folded = bodyText(messages, foldAiProcess = true),
        unfolded = bodyText(messages, foldAiProcess = false),
    )

    private fun bodyText(messages: List<ChatMessage>, foldAiProcess: Boolean): List<String> =
        buildFlatChatItems(messages, foldAiProcess = foldAiProcess).mapNotNull { row ->
            when (row) {
                is FlatChatItem.AssistantMarkdownBlock -> row.rawText
                is FlatChatItem.AssistantLegacyContent -> row.content
                else -> null
            }
        }

    private data class AssistantBody(val folded: List<String>, val unfolded: List<String>)

    private fun assertToolRoundInRequest(messages: List<LLMMessage>, round: ToolRound) {
        val call = messages.single { msg -> msg.contentParts.any { it is AgentContentPart.ToolUse && it.id == round.id } }
        val tool = call.contentParts.filterIsInstance<AgentContentPart.ToolUse>().single { it.id == round.id }
        assertEquals(LLMMessage.Role.ASSISTANT, call.role)
        assertEquals(listOf(round.progress), call.contentParts.filterIsInstance<AgentContentPart.Text>().map { it.text })
        assertEquals(round.name, tool.name)
        assertEquals(round.path, tool.input.getString("path"))

        val resultMessage = messages.single { msg -> msg.contentParts.any { it is AgentContentPart.ToolResult && it.id == round.id } }
        val result = resultMessage.contentParts.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == round.id }
        assertEquals(LLMMessage.Role.USER, resultMessage.role)
        assertTrue(messages.indexOf(call) < messages.indexOf(resultMessage))
        assertEquals(round.name, result.name)
        assertFalse("${round.name} failed: ${result.content}", result.isError)
        assertTrue("Missing real ${round.name} output: ${result.content}", round.expectedOutputLine in result.content.lines())
    }

    private data class ToolRound(
        val id: String,
        val name: String,
        val path: String,
        val progress: String,
        val expectedOutputLine: String,
    ) {
        fun args(): JSONObject = JSONObject().put("path", path).put("tool_title", "Inspect the sample workspace")
    }

    private class ScriptedProvider(
        val script: suspend FlowCollector<LLMStreamChunk>.(Int) -> Unit,
    ) : LLMProvider {
        override val name = "reply-boundary-fixture"
        override var model = LLMModel.claudeHaiku45
        val requests = mutableListOf<List<LLMMessage>>()

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            systemStablePrefixLen: Int,
            extraHeaders: Map<String, String>,
        ): Flow<LLMStreamChunk> = flow {
            requests.add(messages.toList())
            script(requests.size)
        }

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            extraHeaders: Map<String, String>,
        ): LLMResponse = error("The fixture only scripts streaming model requests")
    }

    private class PlainPrefsOps(private val context: Context) : EncryptedPrefsFactory.Ops {
        override fun build(fileName: String): SharedPreferences =
            context.getSharedPreferences("reply-boundary-$fileName", Context.MODE_PRIVATE)

        override fun masterKeyWorks(): Boolean = true
        override fun discardStore(fileName: String) { context.deleteSharedPreferences("reply-boundary-$fileName") }
        override fun deleteMasterKey() = Unit
        override fun inMemory(): SharedPreferences = build("in-memory")
        override fun log(priority: Int, msg: String, t: Throwable?) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val SESSION_ID = "agent-final-reply-boundary-fixture"
        const val WORKSPACE = "/var/minis/workspace"
        const val USER_PROMPT = "Check the sample note and show me the result."
        const val FINAL_REPLY = "The sample color is amber, recorded in note.txt."
    }
}
