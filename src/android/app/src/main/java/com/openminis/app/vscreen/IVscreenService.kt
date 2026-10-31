package com.openminis.app.vscreen

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/** Writes the AIDL-conventional interface token. */
private fun Parcel.writeToken() {
    writeInterfaceToken(IVscreenService.DESCRIPTOR)
}

/** Enforces the AIDL-conventional interface token on the server side. */
private fun Parcel.checkToken() {
    enforceInterface(IVscreenService.DESCRIPTOR)
}

/**
 * [T-android-vscreen2] Vscreen 2.0 Shizuku user service interface, written
 * as a HAND-ROLLED binder contract instead of AIDL: this project keeps
 * `buildFeatures { aidl = false }` (AGP 8 default) and the gradle file is
 * outside this feature's ownership, so the interface marshals its own
 * parcels. The wire format follows the AIDL convention exactly
 * (interface-token, writeNoException, int-encoded booleans), so switching
 * to a generated AIDL later stays byte-compatible.
 *
 * The implementation ([VscreenUserService]) runs inside a Shizuku-created
 * process under the shell uid (2000), which can call the hidden
 * IActivityTaskManager / IWindowManager binder APIs that ordinary apps and
 * even the Shizuku shell one-liners cannot address reliably (task listing,
 * task moves across displays, per-display IME policy). All Android-level
 * probing happens in the implementation; every failure is reported honestly
 * as false / "" — never as a guessed fallback.
 *
 * Bump [VscreenUserService.VERSION] (it is passed to
 * Shizuku.UserServiceArgs.version) whenever [VscreenUserService] changes so
 * Shizuku restarts the service.
 */
interface IVscreenService : IInterface {

    /**
     * The top task of [displayId] as "taskId\tpackage\ttopActivity", or ""
     * when the display has no task or the ROM surface is unreachable.
     */
    fun listTopTask(displayId: Int): String

    /**
     * Every task the shell uid can see, one
     * "taskId\tdisplayId\tpackage\ttopActivity" line per task. "" when none.
     */
    fun listTasks(): String

    /**
     * IActivityTaskManager.moveRootTaskToDisplay (API 31+), falling back to
     * IActivityManager.moveTaskToDisplay / moveStackToDisplay on older
     * versions — whichever method this ROM actually exposes. False when the
     * move was refused or no variant exists.
     */
    fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean

    /**
     * IWindowManager.setDisplayImePolicy — keeps the soft keyboard from
     * being pulled to (or from) [displayId]. False on ROMs without the API.
     */
    fun setDisplayImePolicy(displayId: Int, policy: Int): Boolean

    /** Asks the service process to exit (Shizuku stops unbound services anyway). Oneway. */
    fun destroy()

    companion object {
        const val DESCRIPTOR = "com.openminis.app.vscreen.IVscreenService"

        // Transaction codes (kept stable; destroy uses the Shizuku detach code).
        const val CODE_LIST_TOP_TASK = 1
        const val CODE_LIST_TASKS = 2
        const val CODE_MOVE_ROOT_TASK = 3
        const val CODE_SET_IME_POLICY = 4
        const val CODE_DESTROY = 16777114

        /** Marshals the binder Shizuku handed over into an [IVscreenService]. */
        fun asInterface(binder: IBinder?): IVscreenService? {
            if (binder == null) return null
            val local = binder.queryLocalInterface(DESCRIPTOR)
            return (local as? IVscreenService) ?: Proxy(binder)
        }
    }

    /** Server side: extend this in the Shizuku shell-uid process. */
    abstract class Stub : Binder(), IVscreenService {

        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                CODE_LIST_TOP_TASK -> {
                    data.checkToken()
                    reply?.writeNoException()
                    reply?.writeString(listTopTask(data.readInt()))
                }
                CODE_LIST_TASKS -> {
                    data.checkToken()
                    reply?.writeNoException()
                    reply?.writeString(listTasks())
                }
                CODE_MOVE_ROOT_TASK -> {
                    data.checkToken()
                    val taskId = data.readInt()
                    val displayId = data.readInt()
                    reply?.writeNoException()
                    reply?.writeInt(if (moveRootTaskToDisplay(taskId, displayId)) 1 else 0)
                }
                CODE_SET_IME_POLICY -> {
                    data.checkToken()
                    val displayId = data.readInt()
                    val policy = data.readInt()
                    reply?.writeNoException()
                    reply?.writeInt(if (setDisplayImePolicy(displayId, policy)) 1 else 0)
                }
                CODE_DESTROY -> {
                    // Oneway semantics: the process exits without a reply.
                    destroy()
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    /** Client side: wraps the remote binder Shizuku handed to the connection. */
    private class Proxy(private val remote: IBinder) : IVscreenService {
        override fun asBinder(): IBinder = remote

        override fun listTopTask(displayId: Int): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeToken()
                data.writeInt(displayId)
                remote.transact(CODE_LIST_TOP_TASK, data, reply, 0)
                reply.readException()
                reply.readString() ?: ""
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun listTasks(): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeToken()
                remote.transact(CODE_LIST_TASKS, data, reply, 0)
                reply.readException()
                reply.readString() ?: ""
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeToken()
                data.writeInt(taskId)
                data.writeInt(displayId)
                remote.transact(CODE_MOVE_ROOT_TASK, data, reply, 0)
                reply.readException()
                reply.readInt() != 0
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun setDisplayImePolicy(displayId: Int, policy: Int): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeToken()
                data.writeInt(displayId)
                data.writeInt(policy)
                remote.transact(CODE_SET_IME_POLICY, data, reply, 0)
                reply.readException()
                reply.readInt() != 0
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun destroy() {
            val data = Parcel.obtain()
            try {
                data.writeToken()
                remote.transact(CODE_DESTROY, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }
    }
}
