package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-gomobile-optional-net] Calls the Go network core through reflection, so a
 * binding that has not shipped an entry point yet cannot break the build.
 *
 * Why this exists: `src/rcloneStub` declares the full `ts*` / `net*` contract so
 * Settings > Network compiles without the Go AAR, but the AAR that CI actually
 * builds (`deps/rclone-mobile/gomobile/gomobile.go`, exported via gomobile)
 * currently exposes only `rcloneInitialize` / `rcloneFinalize` / `rcloneRPC`.
 * Kotlin that called the stub names directly therefore compiled in the
 * unit-test job (stub present) and failed in the APK job with
 * `Unresolved reference 'netDiagnose'` — which is exactly how #264 and #265
 * broke both rolling builds while every test job stayed green.
 *
 * Reflection turns "the binding lacks this entry point" from a compile error
 * into the same runtime envelope the stub returns, so the two artifacts behave
 * identically and either one can be present:
 *
 * - method missing → `{"ok":false,"error":"…unavailable…"}` (never throws)
 * - method present → invoked directly, result returned verbatim
 *
 * The Go side only has to add the export; no Kotlin change is needed when it
 * lands, because lookup happens per call.
 */
internal object GomobileNet {

    private const val TAG = "GomobileNet"

    /** Class holding the gomobile statics; absent when no AAR and no stub. */
    private const val BINDING = "com.openminis.rclone.gomobile.Gomobile"

    /** Interface the Go side uses for log lines; also optional. */
    private const val LOG_SINK = "com.openminis.rclone.gomobile.LogSink"

    private val bindingClass: Class<*>? by lazy {
        runCatching { Class.forName(BINDING) }
            .onFailure { AppLogger.warning(TAG, "binding class $BINDING not on classpath") }
            .getOrNull()
    }

    /** One warning per missing entry point, not one per call. */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun unavailable(entry: String): String =
        """{"ok":false,"error":"the Go network core ($entry) is not in this rclone.aar build; run ./deps/build_rclone_android.sh"}"""

    private fun logMissing(entry: String) {
        if (reported.add(entry)) {
            AppLogger.warning(TAG, "$entry missing from the gomobile binding; returning unavailable envelope")
        }
    }

    private fun find(name: String, argc: Int): Method? =
        bindingClass?.methods?.firstOrNull { it.name == name && it.parameterCount == argc }

    private fun coerce(type: Class<*>, value: Any?): Any? = when {
        value == null -> null
        type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> (value as? Number)?.toLong()
        type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java -> (value as? Number)?.toInt()
        type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> value as? Boolean
        else -> value
    }

    /** Invokes a static returning String; falls back to an envelope on absence. */
    private fun stringCall(entry: String, vararg args: Any?): String {
        val method = find(entry, args.size)
        if (method == null) {
            logMissing(entry)
            return unavailable(entry)
        }
        return runCatching {
            val typed = args.mapIndexed { i, v -> coerce(method.parameterTypes[i], v) }.toTypedArray()
            (method.invoke(null, *typed) as? String) ?: unavailable(entry)
        }.getOrElse {
            """{"ok":false,"error":"$entry: ${it.javaClass.simpleName}"}"""
        }
    }

    /** Invokes a static returning void; absence is a no-op, never a crash. */
    private fun voidCall(entry: String, vararg args: Any?) {
        val method = find(entry, args.size) ?: run {
            logMissing(entry)
            return
        }
        runCatching {
            val typed = args.mapIndexed { i, v -> coerce(method.parameterTypes[i], v) }.toTypedArray()
            method.invoke(null, *typed)
        }.onFailure { AppLogger.warning(TAG, "$entry failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Registers a Go log sink.
     *
     * Built as a dynamic proxy over the binding's own `LogSink` interface so no
     * Kotlin type from the stub leaks into the call site (the real AAR has no
     * such class, and importing it is what broke `compileReleaseKotlin`).
     */
    fun tsSetLogger(onLine: (String) -> Unit): String {
        val method = find("tsSetLogger", 1) ?: run {
            logMissing("tsSetLogger")
            return unavailable("tsSetLogger")
        }
        val sinkType = runCatching { Class.forName(LOG_SINK) }.getOrNull()
            ?: return unavailable("tsSetLogger")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            sinkType.classLoader,
            arrayOf(sinkType),
        ) { _, m, a ->
            when {
                m.name == "line" -> runCatching { onLine((a?.firstOrNull() as? String).orEmpty()) }
                else -> null
            }
        }
        return runCatching {
            (method.invoke(null, proxy) as? String) ?: unavailable("tsSetLogger")
        }.getOrElse { unavailable("tsSetLogger") }
    }

    // ---- embedded Tailscale node (tsnet) ----

    fun tsStart(authKey: String, hostname: String, stateDir: String): String =
        stringCall("tsStart", authKey, hostname, stateDir)

    fun tsSetAcceptRoutes(accept: Boolean): String = stringCall("tsSetAcceptRoutes", accept)

    fun tsSetExitNode(ip: String): String = stringCall("tsSetExitNode", ip)

    fun tsStop(): String = stringCall("tsStop")

    fun tsLogout(): String = stringCall("tsLogout")

    fun tsStatus(): String = stringCall("tsStatus")

    fun tsRoutes(): String = stringCall("tsRoutes")

    fun tsDialTest(address: String, timeoutMs: Long): String = stringCall("tsDialTest", address, timeoutMs)

    fun tsSetInterfaces(interfacesJson: String): String = stringCall("tsSetInterfaces", interfacesJson)

    fun tsSetDefaultRouteInterface(ifName: String) = voidCall("tsSetDefaultRouteInterface", ifName)

    fun tsNetworkChanged() = voidCall("tsNetworkChanged")

    // ---- local entry, transparent forwarding, diagnostics (nethub) ----

    fun netConfigure(configJson: String): String = stringCall("netConfigure", configJson)

    fun netStop(): String = stringCall("netStop")

    fun netHookRoutes(tag: String, localJson: String): String = stringCall("netHookRoutes", tag, localJson)

    fun netHookRoutes2(tag: String, localJson: String, resolversJson: String): String =
        stringCall("netHookRoutes2", tag, localJson, resolversJson)

    fun netXparentStart(mapDir: String, tag: String): String = stringCall("netXparentStart", mapDir, tag)

    fun netShellProbe(resolver: String, namesJson: String, timeoutMs: Long): String =
        stringCall("netShellProbe", resolver, namesJson, timeoutMs)

    fun netXparentStop(): String = stringCall("netXparentStop")

    fun netXparentStatus(): String = stringCall("netXparentStatus")

    fun netStatus(): String = stringCall("netStatus")

    fun netHealth(): String = stringCall("netHealth")

    fun netSelfTest(timeoutMs: Long): String = stringCall("netSelfTest", timeoutMs)

    fun netDiagnose(timeoutMs: Long): String = stringCall("netDiagnose", timeoutMs)

    fun netDecide(tag: String, host: String): String = stringCall("netDecide", tag, host)

    fun netProbe(tag: String, address: String, timeoutMs: Long): String =
        stringCall("netProbe", tag, address, timeoutMs)

    fun netTestUpstream(upstreamJson: String, target: String, timeoutMs: Long): String =
        stringCall("netTestUpstream", upstreamJson, target, timeoutMs)

    /** SFTP/FTP `socks_proxy` (rclone-route); also absent from older AARs. */
    fun rcloneSetSocks(socks: String): String = stringCall("rcloneSetSocks", socks)
}
