package com.openminis.app.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Host-side endpoint of the proot `native_offload` extension.
 *
 * The guest issues `execve("<handler-name>", argv, envp)`; the proot
 * extension sends argv/env/cwd over an abstract unix socket to this
 * server. The server dispatches to the registered [NativeOffloadHandler],
 * writes the handler's combined output into a tmpfile inside the guest's
 * /tmp, and replies with `(exit_code, guest_tmpfile_path)`. The extension
 * then rewrites the execve into `/bin/cat <tmpfile>` so the guest sees
 * the handler output as plain stdout.
 *
 * Mirrors iOS `native_offload_add_handler` + `native_offload_exec`.
 */
data class NativeOffloadRequest(
    val pid: Int,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    /**
     * T340: chat session id forwarded by the agent shell via the
     * `MINIS_CHAT_SESSION_ID` env var. Lets [OffloadPermissionManager]
     * scope ASK_ONCE grants/denials per-chat-session instead of using
     * a single process-wide bucket. Null when the offload originates
     * outside a chat (e.g. interactive terminal) — handlers fall back
     * to `OFFLOAD_GLOBAL_SESSION_ID` in that case.
     */
    val sessionId: String? = null,
)

data class NativeOffloadResult(
    val exitCode: Int,
    val output: String,
)

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

/** [T-native-offload-peer-uid] Only this app's own uid may use the offload socket. */
internal object PeerCheck {
    fun allowed(peerUid: Int, myUid: Int): Boolean = peerUid >= 0 && peerUid == myUid
}

/** [T-offload-cloexec-api29] Os.fcntlInt is public SDK API from 30 (R) on. */
internal fun canSetCloseOnExec(sdkInt: Int): Boolean = sdkInt >= 30

object NativeOffloadServer {
    private const val TAG = "NativeOffloadServer"
    /**
     * [T-native-offload-bind] Prefix of the abstract socket name; the full
     * name is [socketName]. It is never used on its own: the bare
     * "native-offload" is what every earlier build bound.
     */
    private const val SOCKET_NAME_PREFIX = "native-offload"
    private const val MAGIC_REQ = 0x46464F4E  // 'N' 'O' 'F' 'F' little-endian
    private const val MAGIC_RSP = 0x52464F4E  // 'N' 'O' 'F' 'R'
    private const val VERSION = 1

    /** [T-android-offload-tmp-leak] Filename prefix of a handler reply file. */
    private const val REPLY_PREFIX = ".native-offload-"

    /**
     * How long a reply file may live before the in-session sweep may remove it.
     *
     * The real gap between writing the file and the rewritten `/bin/cat` reading
     * it is sub-millisecond, so 10 minutes is enormously conservative — it
     * exists only so a stopped/slow tracee can never lose its output.
     */
    private const val REPLY_TTL_MS = 10 * 60 * 1000L

    /** Run the opportunistic sweep every N replies, not on every single one. */
    private const val SWEEP_EVERY_N_REPLIES = 50L

    /**
     * [T-native-offload-bind] The abstract socket this app instance listens
     * on: `native-offload-<uid>-<pid>`, e.g. `native-offload-10379-4242`.
     *
     * Abstract unix names are global to the network namespace, not to the
     * app. A fixed "native-offload" therefore collided with anything else on
     * the device holding that name - a second Minis Ultra in an app-clone / work
     * profile / secondary user (same package, different uid), a fork built
     * from this code, or a leftover process. The bind then failed and, since
     * it threw from Application.onCreate, the app could never start again.
     * The uid differs for every such instance (userId * 100000 + appId).
     *
     * [GH-440] The pid makes the name unique per process as well, so it no
     * longer relies on exactly one process per uid calling [start]: today
     * that holds only because the `:acra` reporter returns from
     * MinisApp.onCreate first, and any process added later would have
     * silently lost offload to whichever bound first. It also means a
     * previous process of this app (or, below API 30 where the socket is not
     * close-on-exec, a guest process that inherited it) can no longer hold
     * the name this one needs. Suggested in the GH #440 patch.
     *
     * proot gets the name only through `--native-offload=<name>:<handlers>`
     * from the four launchers, which all read it here, so the name is not
     * persisted anywhere and the proot extension needs no change.
     */
    val socketName: String by lazy {
        socketNameFor(android.os.Process.myUid(), android.os.Process.myPid())
    }

    internal fun socketNameFor(uid: Int, pid: Int): String = "$SOCKET_NAME_PREFIX-$uid-$pid"

    /** First background retry delays, then [RETRY_STEADY_MS] until it binds. */
    private val RETRY_DELAYS_MS = longArrayOf(50L, 100L, 200L, 400L, 800L, 2_000L, 5_000L, 10_000L)
    private const val RETRY_STEADY_MS = 30_000L

    /** How long a shell launcher waits for a bind that is being retried. */
    const val AWAIT_LISTENING_MS = 2_000L

    /**
     * Launchers wait only within this window after the first bind failed.
     * It covers the usual cause - the previous process of this app still
     * releasing the name for a moment after a restart. Past it, the name is
     * held for real and waiting would only add [AWAIT_LISTENING_MS] to every
     * shell command while degraded.
     */
    private const val AWAIT_WINDOW_MS = 5_000L

    private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()
    private val counter = AtomicLong(0)

    /**
     * [GH #453] Most offload calls served at once; one more is answered
     * "busy" (exit 75) instead of getting a thread. Each call holds a worker
     * thread (about 1 MB of stack) until its handler returns, and the protocol
     * has no cancel message, so a handler that never returns used to leak its
     * thread for the life of the process. The known never-returning waits are
     * now bounded (OffloadPermissionManager.DECISION_TIMEOUT_MS), but the long
     * legitimate ones remain - `minis-scheduled run` waits for a whole agent
     * run (up to ~90 min), `model_use` for a full model reply, and each
     * sub-agent's shell can have one in flight. 128 is far above what those
     * need together and far below where thread creation fails, so this only
     * ever turns an unknown leak into a visible, logged error instead of a
     * crash. Rejecting rather than queueing: a queue behind stuck handlers
     * would hang every later command.
     */
    internal const val MAX_CONCURRENT_CALLS = 128

    /** [GH #453] Exit code of a call refused at [MAX_CONCURRENT_CALLS] (EX_TEMPFAIL). */
    internal const val EXIT_BUSY = 75

    internal val inFlight = OffloadInFlight()
    private val lastBusyLogMs = AtomicLong(0)
    @Volatile
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    /** [T-native-offload-bind] Non-null while a background bind retry runs. */
    @Volatile
    private var retryThread: Thread? = null

    /** When the first bind failed (uptime ms), 0 when not degraded. */
    @Volatile
    private var degradedSinceMs = 0L

    /** True once the server is bound and accepting. */
    val isListening: Boolean get() = serverSocket != null

    @Volatile
    private var rootfsTmpDir: File? = null

    val registeredHandlers: Set<String> get() = handlers.keys.toSet()

    fun register(name: String, handler: NativeOffloadHandler) {
        require(name.isNotEmpty())
        handlers[name] = handler
        Log.d(TAG, "register '$name' (total=${handlers.size})")
    }

    /**
     * Bind the server. Never throws and never sleeps on the caller's thread.
     *
     * [T-native-offload-bind] One synchronous attempt, as before, so on the
     * normal path the socket is listening before onCreate returns and before
     * any shell can start. If that attempt fails (the name is held by
     * someone else), the app keeps running without native offload: the
     * offload CLIs report themselves unavailable (see [awaitListening] and
     * the handler stubs) and a daemon thread retries the bind in the
     * background until it succeeds. Previously this threw from
     * Application.onCreate after ~1.55 s of sleeps on the main thread, and a
     * name held for longer than that put the app into an endless crash loop.
     */
    @Synchronized
    fun start(rootfsDir: File) {
        rootfsTmpDir = File(rootfsDir, "tmp")
        if (serverSocket != null || retryThread != null) return

        val s = tryBind(attempt = 1)
        if (s != null) {
            onBound(s)
            return
        }
        degradedSinceMs = android.os.SystemClock.uptimeMillis()
        Log.w(TAG, "native offload unavailable: '$socketName' is held by another process; " +
            "offload CLIs report unavailable, retrying the bind in the background")
        retryThread = thread(name = "native-offload-rebind", isDaemon = true) { retryBindLoop() }
    }

    /**
     * Wait up to [timeoutMs] for the server to be listening. Returns at once
     * when it already is (the normal case), when no bind is pending, on the
     * main thread, or once [AWAIT_WINDOW_MS] have passed since the first
     * bind failed. Shell launchers pass `--native-offload` only when this
     * returns true: a proot pointed at a name nobody of ours serves either
     * fails each offload silently or, when another process holds the name
     * without accepting, blocks in connect/read forever.
     */
    fun awaitListening(timeoutMs: Long = AWAIT_LISTENING_MS): Boolean {
        if (serverSocket != null) return true
        if (retryThread == null) return false
        if (android.os.Looper.getMainLooper()?.isCurrentThread == true) return false
        val sinceFail = android.os.SystemClock.uptimeMillis() - degradedSinceMs
        if (sinceFail > AWAIT_WINDOW_MS) return false
        val deadline = System.currentTimeMillis() + minOf(timeoutMs, AWAIT_WINDOW_MS - sinceFail)
        while (serverSocket == null && retryThread != null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        return serverSocket != null
    }

    private fun tryBind(attempt: Int): LocalServerSocket? =
        try {
            LocalServerSocket(socketName)
        } catch (e: java.io.IOException) {
            if (attempt <= RETRY_DELAYS_MS.size + 1 || attempt % 10 == 0) {
                Log.w(TAG, "bind '$socketName' attempt $attempt failed: ${e.message}")
            }
            null
        }

    /**
     * [T-native-offload-cloexec] Keep the listening socket out of every
     * process we exec. Without FD_CLOEXEC the terminal's proot and every
     * guest process under it (fork+execve in pty_bridge.c) held the bound
     * name, so one of them outliving the app would keep the name taken and
     * every later launch would find it in use. Nothing needs to inherit it:
     * proot connects by name.
     */
    private fun setCloseOnExec(s: LocalServerSocket) {
        // [T-offload-cloexec-api29] Os.fcntlInt is public only from API 30. On
        // 26-29 it is a greylisted hidden method: linking it throws
        // NoSuchMethodError - an Error, not an Exception - which killed the
        // process from the bind-retry thread the first time a shell started
        // (Android 10, 1.15-beta.3). Below 30 the socket stays inheritable, as
        // it was before f61d6f472; a stale holder of the name is already
        // handled by the bind-retry loop.
        if (!canSetCloseOnExec(android.os.Build.VERSION.SDK_INT)) {
            Log.i(TAG, "FD_CLOEXEC on the offload socket skipped below API 30")
            return
        }
        try {
            android.system.Os.fcntlInt(s.fileDescriptor, android.system.OsConstants.F_SETFD, android.system.OsConstants.FD_CLOEXEC)
        } catch (t: Throwable) {
            Log.w(TAG, "FD_CLOEXEC on the offload socket failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun retryBindLoop() {
        var attempt = 1
        while (true) {
            val delay = RETRY_DELAYS_MS.getOrElse(attempt - 1) { RETRY_STEADY_MS }
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return
            }
            attempt++
            val s = tryBind(attempt) ?: continue
            synchronized(this) {
                retryThread = null
                degradedSinceMs = 0L
                onBound(s)
            }
            Log.i(TAG, "native offload recovered after $attempt bind attempts")
            return
        }
    }

    private fun onBound(s: LocalServerSocket) {
        setCloseOnExec(s)
        serverSocket = s
        acceptThread = thread(name = "native-offload-accept", isDaemon = true) {
            runAcceptLoop(s)
        }
        Log.i(TAG, "listening on abstract socket '$socketName' " +
            "handlers=${handlers.keys.sorted()} tmpDir=${rootfsTmpDir?.absolutePath}")

        // [T-android-offload-tmp-leak] Sweep reply files orphaned by earlier
        // app processes. See sweepStaleReplies for why this is safe here and
        // why the mechanism leaks in the first place.
        sweepStaleReplies(all = true)
    }

    /**
     * [T-android-offload-tmp-leak] Delete `.native-offload-*` reply files.
     *
     * WHY THESE LEAK. Each offload call writes the handler's combined output to
     * `<rootfs>/tmp/.native-offload-<pid>-<seq>` and returns the GUEST path;
     * proot's native_offload extension then rewrites the tracee's execve into
     * `/bin/cat <tmpfile>`. So the host cannot delete the file at reply time —
     * `cat` has not run yet, and deleting it would turn every offload call into
     * "No such file or directory". Nothing else ever removed them either
     * (`delete`/`cleanup` were zero occurrences in this file), so one file
     * accumulated per offload call, forever: measured on a dev device, 35 files
     * spanning 12 days and surviving many app restarts, growing +1 per call.
     * On a heavy user's device this reached thousands of files / GBs, showing
     * up as an inflated "Shell container" figure on the storage screen.
     *
     * WHY DELETING IS SAFE HERE.
     *  - [all] = true is used at server start. Any file present then belongs to
     *    a PREVIOUS app process: its tracee died with that process, so no `cat`
     *    can still be pending on it.
     *  - [all] = false keeps files younger than [REPLY_TTL_MS] and is used
     *    opportunistically while serving. The TTL is what makes it safe: it only
     *    ever removes files far older than the microseconds between writing the
     *    reply and the rewritten `cat` reading it. A pathologically stopped
     *    tracee (SIGSTOP between execve-enter and the read) is the sole way to
     *    exceed it, and that already means the caller is not reading its output.
     *
     * Failures are logged and swallowed: a leaked temp file must never break an
     * offload call.
     */
    private fun sweepStaleReplies(all: Boolean) {
        val dir = rootfsTmpDir ?: return
        try {
            val cutoff = System.currentTimeMillis() - REPLY_TTL_MS
            var removed = 0
            var bytes = 0L
            dir.listFiles { f -> f.isFile && f.name.startsWith(REPLY_PREFIX) }?.forEach { f ->
                if (!all && f.lastModified() > cutoff) return@forEach
                val len = f.length()
                if (f.delete()) {
                    removed++
                    bytes += len
                }
            }
            if (removed > 0) {
                Log.i(TAG, "swept $removed stale reply file(s), freed ${bytes / 1024}KB (all=$all)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweepStaleReplies failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        retryThread?.interrupt()
        retryThread = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    private fun runAcceptLoop(s: LocalServerSocket) {
        while (true) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                Log.i(TAG, "accept loop terminated: ${e.message}")
                return
            }
            Log.d(TAG, "accepted client from proot extension")
            if (!inFlight.tryAdmit(MAX_CONCURRENT_CALLS)) {
                rejectBusy(client)
                continue
            }
            try {
                thread(name = OffloadInFlight.WORKER_THREAD_NAME, isDaemon = true) {
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        Log.w(TAG, "worker error: ${e.message}", e)
                    } finally {
                        inFlight.release()
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (t: Throwable) {
                // pthread_create failed (GH #453's OutOfMemoryError): refuse
                // this call and keep accepting rather than lose the server.
                inFlight.release()
                Log.w(TAG, "could not start an offload worker: $t")
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * [GH #453] Answer a call over [MAX_CONCURRENT_CALLS] on the accept thread:
     * read its request (proot sends it at once; 2 s read timeout), reply busy
     * with the commands currently holding the slots.
     */
    private fun rejectBusy(client: LocalSocket) {
        val busy = inFlight.summary()
        val now = System.currentTimeMillis()
        val last = lastBusyLogMs.get()
        if (now - last > 60_000 && lastBusyLogMs.compareAndSet(last, now)) {
            Log.w(TAG, "[GH453] refusing offload call: $MAX_CONCURRENT_CALLS calls in flight ($busy)")
        }
        try {
            client.soTimeout = 2_000
            val peerUid = try { client.peerCredentials.uid } catch (e: Exception) { -1 }
            if (!PeerCheck.allowed(peerUid, android.os.Process.myUid())) return
            val request = readRequest(DataInputStream(client.inputStream)) ?: return
            writeReply(
                DataOutputStream(client.outputStream),
                request.pid,
                NativeOffloadResult(
                    exitCode = EXIT_BUSY,
                    output = "native_offload: busy - $MAX_CONCURRENT_CALLS offload calls are still running ($busy). " +
                        "Try again when some finish.\n",
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "busy reply failed: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** [GH #453] `command:count(oldest Ns)` for the calls in flight, for logs. */
    fun inFlightSummary(): String = inFlight.summary()

    /** [T-native-offload-peer-uid] First accepted peer uid, logged once. */
    @Volatile
    private var loggedPeerUid = false

    private fun handleClient(client: LocalSocket) {
        // [T-native-offload-peer-uid] Abstract sockets have no filesystem
        // permissions: any app on the device could connect and drive these
        // handlers (contacts, photos, model calls with the user's keys). The
        // only legitimate client is proot - it connects from the tracer
        // process itself, which always runs as this app's uid (`-0` fakes
        // root only inside the guest). Refuse every other uid.
        val peerUid = try { client.peerCredentials.uid } catch (e: Exception) { -1 }
        if (!PeerCheck.allowed(peerUid, android.os.Process.myUid())) {
            Log.w(TAG, "refused offload client uid=$peerUid (expected ${android.os.Process.myUid()})")
            return
        }
        if (!loggedPeerUid) {
            loggedPeerUid = true
            Log.i(TAG, "offload client uid=$peerUid accepted (app uid)")
        }
        val request = readRequest(DataInputStream(client.inputStream)) ?: return
        val output = DataOutputStream(client.outputStream)
        val pid = request.pid
        val argv = request.argv
        val env = request.env
        val cwd = request.cwd

        val name = argv.firstOrNull().orEmpty().substringAfterLast('/')
        Log.d(TAG, "recv pid=$pid name='$name' argc=${argv.size} argv=$argv cwd=$cwd envc=${env.size}")

        val t0 = System.nanoTime()
        val handler = handlers[name]
        // [GH #453] Name the thread after its command, so a thread count by
        // name (JankDiag, tombstone summary) says which command holds them.
        if (handler != null) {
            runCatching { Thread.currentThread().name = OffloadInFlight.workerThreadName(name) }
            inFlight.begin(name)
        }
        val result = try {
            if (handler == null) {
                Log.w(TAG, "no handler registered for '$name' (known=${handlers.keys})")
                NativeOffloadResult(exitCode = 127, output = "native_offload: no handler for '$name'\n")
            } else {
                try {
                    handler.handle(NativeOffloadRequest(
                        pid = pid,
                        argv = argv,
                        env = env,
                        cwd = cwd,
                        sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "handler '$name' threw: ${e.message}", e)
                    NativeOffloadResult(exitCode = 1, output = "native_offload: ${e.message}\n")
                }
            }
        } finally {
            if (handler != null) inFlight.end()
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val tmpGuest = writeReply(output, pid, result)
        Log.d(TAG, "reply name='$name' exit=${result.exitCode} outBytes=${result.output.length} " +
            "tmpGuest=$tmpGuest elapsed=${elapsedMs}ms")
    }

    private class WireRequest(val pid: Int, val argv: List<String>, val env: Map<String, String>, val cwd: String)

    /** The request frame, or null (logged) for a bad magic / version. */
    private fun readRequest(input: DataInputStream): WireRequest? {
        val magic = input.readLEInt()
        if (magic != MAGIC_REQ) {
            Log.w(TAG, "bad magic: 0x${magic.toUInt().toString(16)}")
            return null
        }
        val version = input.readLEInt()
        if (version != VERSION) {
            Log.w(TAG, "unsupported version $version")
            return null
        }

        val pid = input.readLEInt()
        val argc = input.readLEInt()
        if (argc < 0 || argc > 256) throw IllegalStateException("bad argc=$argc")
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(input.readLEString()) }

        val envc = input.readLEInt()
        if (envc < 0 || envc > 4096) throw IllegalStateException("bad envc=$envc")
        val env = LinkedHashMap<String, String>(envc)
        repeat(envc) {
            val s = input.readLEString()
            val eq = s.indexOf('=')
            if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
        }
        val cwd = input.readLEString()
        return WireRequest(pid, argv, env, cwd)
    }

    /** Write [result]'s output to its reply file and send the response frame; returns the guest path. */
    private fun writeReply(output: DataOutputStream, pid: Int, result: NativeOffloadResult): String {
        val tmpDir = rootfsTmpDir ?: throw IllegalStateException("server not started")
        tmpDir.mkdirs()
        val seq = counter.incrementAndGet()
        val tmpHost = File(tmpDir, "$REPLY_PREFIX$pid-$seq")
        tmpHost.writeText(result.output)
        val tmpGuest = "/tmp/${tmpHost.name}"

        // [T-android-offload-tmp-leak] Bound growth WITHIN a long-running
        // process. The start-time sweep only catches files from previous
        // processes, but a single session can issue thousands of offload calls
        // (agent loops shelling out repeatedly), so without this the leak simply
        // moves from "across restarts" to "within one run". Age-gated, so the
        // file just written — and any other still awaiting its `cat` — is never
        // touched. Sampled rather than run per reply to keep the hot path cheap.
        if (seq % SWEEP_EVERY_N_REPLIES == 0L) sweepStaleReplies(all = false)

        output.writeLEInt(MAGIC_RSP)
        output.writeLEInt(result.exitCode)
        output.writeLEString(tmpGuest)
        output.flush()
        return tmpGuest
    }

    // ---- little-endian helpers ----

    private fun DataInputStream.readLEInt(): Int {
        val buf = ByteArray(4)
        readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun DataInputStream.readLEString(): String {
        val len = readLEInt()
        if (len < 0 || len > 1 shl 20) throw IllegalStateException("bad string len $len")
        if (len == 0) return ""
        val buf = ByteArray(len)
        readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeLEInt(v: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(buf)
    }

    private fun DataOutputStream.writeLEString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLEInt(bytes.size)
        if (bytes.isNotEmpty()) write(bytes)
    }
}
