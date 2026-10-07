package com.openminis.app.mcp.server

import android.content.Context
import android.util.Log
import com.openminis.app.security.SecurityGateHolder
import com.openminis.app.security.PermissionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide lifecycle manager for the built-in MCP server.
 *
 * Startup: call [init] once (e.g. from [com.openminis.app.MinisApp.onCreate]).
 * Then [start]/[stop]/[restart] are safe to call from settings UI or boot hooks.
 */
object McpServerManager {

    private const val TAG = "McpServerManager"

    enum class Status { STOPPED, RUNNING, ERROR }

    data class ServerState(
        val status: Status = Status.STOPPED,
        val error: String? = null,
        val handledRequests: Long = 0,
    )

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var context: Context? = null
    private var configStore: McpServerConfigStore? = null
    private var server: McpHttpServer? = null
    private var dispatcher: McpToolDispatcher? = null

    /** App version reported in the MCP initialize serverInfo. */
    private var serverVersion: String = "0.0.0"

    /** One-time initialisation from Application.onCreate. Idempotent. */
    fun init(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        configStore = McpServerConfigStore(appContext)
        serverVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"
        ensureMcpServerWorkspace(appContext)
        Log.i(TAG, "McpServerManager initialized")
    }

    /** Start the server on the configured port (or default). */
    @Synchronized
    fun start(port: Int = 0): Boolean {
        val ctx = context ?: return false.also {
            _state.value = ServerState(Status.ERROR, "not initialized")
        }
        val store = configStore!!
        val effectivePort = if (port in McpHttpServer.MIN_PORT..McpHttpServer.MAX_PORT) port else store.config.value.port
        if (!McpHttpServer.validPort(effectivePort)) {
            _state.value = ServerState(Status.ERROR, "invalid port $effectivePort")
            return false
        }

        // Stop existing instance first
        stopInternal()

        // shell policy (auto/strict) maps onto the gate's PermissionMode; read
        // live per request so a settings toggle takes effect without restarting.
        val d = McpToolDispatcher(
            context = ctx,
            gate = SecurityGateHolder.gate,
            shellMode = {
                when (store.currentShellPolicy()) {
                    McpServerConfigStore.SHELL_POLICY_STRICT -> PermissionMode.ASK
                    else -> PermissionMode.ALLOW_ALL
                }
            },
        )
        dispatcher = d
        var started: McpHttpServer? = null
        val s = McpHttpServer(
            context = ctx,
            dispatcher = d,
            authToken = { store.currentToken() },
            serverVersion = serverVersion,
            onRequestHandled = { started?.let { publishState(it) } },
        )
        started = s
        val ok = s.start(effectivePort)
        if (ok) {
            server = s
            _state.value = ServerState(Status.RUNNING, handledRequests = s.handledRequestCount)
            Log.i(TAG, "server started on port $effectivePort")
        } else {
            _state.value = ServerState(Status.ERROR, s.lastError ?: "failed to start")
        }
        return ok
    }

    @Synchronized
    fun stop() {
        stopInternal()
        _state.value = ServerState(Status.STOPPED)
    }

    @Synchronized
    fun restart(port: Int = 0): Boolean {
        stop()
        return start(port)
    }

    private fun stopInternal() {
        server?.stop()
        server = null
        dispatcher = null
    }

    private fun publishState(server: McpHttpServer) {
        _state.value = ServerState(
            status = Status.RUNNING,
            handledRequests = server.handledRequestCount,
        )
    }

    /**
     * Called from MinisApp.onCreate – if the config says enabled, boot the server
     * immediately.
     */
    @Synchronized
    fun bootIfEnabled() {
        val ctx = context ?: return
        val store = configStore ?: return
        if (store.config.value.enabled) {
            start()
        }
    }

    fun currentPort(): Int = configStore?.config?.value?.port ?: McpHttpServer.DEFAULT_PORT

    fun configStore(): McpServerConfigStore? = configStore
}