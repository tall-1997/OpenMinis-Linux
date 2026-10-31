package com.openminis.app.vscreen

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.openminis.app.logging.AppLogger
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T-android-vscreen2] Client for the [VscreenUserService] Shizuku user
 * service (binder-level task listing / moving / IME policy).
 *
 * Binding model: `Shizuku.bindUserService` is asynchronous, so [obtain]
 * blocks on a latch for at most [BIND_TIMEOUT_MS] and returns null on ANY
 * failure (binder down, no permission, Shizuku's user-service machinery
 * unavailable). Callers MUST treat null as "user service unavailable" and
 * degrade to the shell probe path — never retry-spam bind.
 *
 * The service process lives only while bound (no daemon mode): Shizuku stops
 * it when the app process goes away, and nothing persistent runs there (the
 * mute ledger lives in THIS process's prefs, so a service restart loses
 * nothing).
 */
class VscreenServiceClient(private val appContext: Context) {

    private val lock = Any()

    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext, VscreenUserService::class.java),
    )
        .processNameSuffix("vscreen")
        .debuggable(false)
        .version(VscreenUserService.VERSION)

    @Volatile private var service: IVscreenService? = null
    private var connection: ServiceConnection? = null

    /** Latest live binder, or null. A dead cached binder is dropped. */
    fun obtain(): IVscreenService? {
        service?.let { s ->
            if (runCatching { s.asBinder().pingBinder() }.getOrDefault(false)) return s
            service = null
        }
        synchronized(lock) {
            service?.let { s ->
                if (runCatching { s.asBinder().pingBinder() }.getOrDefault(false)) return s
                service = null
            }
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return null
            if (!runCatching { Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }
                    .getOrDefault(false)
            ) {
                return null
            }
            val latch = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    runCatching { IVscreenService.asInterface(binder) }
                        .onSuccess { s -> service = s; latch.countDown() }
                        .onFailure {
                            AppLogger.warning(TAG, "asInterface failed: ${it.message}")
                            latch.countDown()
                        }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                }
            }
            connection = conn
            val bound = runCatching { Shizuku.bindUserService(args, conn) }
                .onFailure { AppLogger.warning(TAG, "bindUserService failed: ${it.message}") }
                .isSuccess
            if (!bound) return null
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val obtained = service
            if (obtained == null) {
                AppLogger.warning(TAG, "user service not connected within ${BIND_TIMEOUT_MS}ms")
                runCatching { Shizuku.unbindUserService(args, conn, false) }
                connection = null
            }
            return obtained
        }
    }

    /** Drops the cached binder and asks Shizuku to unbind. Safe to call repeatedly. */
    fun unbind() {
        synchronized(lock) {
            service = null
            connection?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
            connection = null
        }
    }

    companion object {
        private const val TAG = "VscreenServiceClient"

        /**
         * First-bind latency on real devices is well under 1s; 5s covers a
         * cold Shizuku service process start without hanging a tool call for
         * long when the machinery is simply absent.
         */
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
