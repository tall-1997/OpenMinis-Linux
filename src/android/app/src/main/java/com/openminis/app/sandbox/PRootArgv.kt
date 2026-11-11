package com.openminis.app.sandbox

import android.util.Log

/** Log tag preserved from PRootKernel so proot-argv logging is unchanged. */
private const val TAG = "PRootKernel"

    /**
     * Shared proot argv prefix for every spawn path — one-shot shell commands
     * ([buildProotCommand]), the interactive Termux PTY
     * (TerminalSession.buildTermuxArgs), the persistent shell
     * (PersistentShell.startProcess), and RootfsManager's in-guest
     * apt/pip/mirror helpers. These were four hand-rolled copies of the same
     * flag list that had already drifted; they now share this builder and
     * append only their own tail.
     *
     * Produces, in exactly the order all four former call sites used:
     * ```
     * <proot> -0 --link2symlink --kill-on-exit -r <rootfs>
     *         -b /dev -b /proc [-b /sys] -w /root
     *         [-b <host>:<linux> ...] [--native-offload=<socket>:<handlers>]
     * ```
     *
     * `-0` runs as fake root (uid 0). `--link2symlink` [T141] translates
     * hardlink() to symlink(): Android's /data refuses cross-directory
     * hardlinks for app uids (EPERM), which broke busybox-applet installs.
     * `--kill-on-exit` reaps the guest when the host process dies.
     *
     * Recorded per-caller differences kept as parameters (do not "normalize"
     * them away):
     *  - RootfsManager helpers pass bindMounts = null and
     *    includeNativeOffload = false, and all but the apt-install run pass
     *    includeSys = false — binding only /dev+/proc is their long-standing
     *    behavior and the offload socket is not needed for apt/pip work.
     *  - [bindMounts] is the CALLER's map: the one-shot command and the Termux
     *    PTY pass the process-global [bindMounts]; PersistentShell passes its
     *    per-session snapshot.
     */
internal fun commonArgv(
        prootPath: String,
        rootfsDir: String,
        bindMounts: Map<String, String>? = null,
        includeSys: Boolean = true,
        includeNativeOffload: Boolean = false,
    ): MutableList<String> {
        val cmd = mutableListOf(
            prootPath,
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            rootfsDir,
        )
        // Bind essential pseudo-filesystems.
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        if (includeSys) { cmd.add("-b"); cmd.add("/sys") }
        // Working directory
        cmd.add("-w"); cmd.add("/root")

        if (bindMounts != null) {
            for ((linuxPath, hostPath) in bindMounts) {
                cmd.add("-b")
                cmd.add("$hostPath:$linuxPath")
                // Log external-folder binds specifically — these are the ones
                // that read empty when the host view is filtered, and the
                // proot child's own EACCES on readdir is otherwise invisible
                // in the log file. (Formerly only buildProotCommand logged
                // this; the PTY/persistent-shell paths silently shared the
                // same binds.)
                if (linuxPath.startsWith(PRootKernel.MOUNTS_LINUX_PREFIX)) {
                    Log.i(TAG, "proot bind mount: $hostPath -> $linuxPath")
                }
            }
        }

        // Native offload: route registered handler names to the host-side
        // NativeOffloadServer over the abstract unix socket.
        //
        // [OpenMinis#439] The socket name is per-process
        // ([NativeOffloadServer.socketName]), so an app clone / second user
        // binds its own and is not degraded; both sides read the same getter in
        // the same process. The guest never sees the name — proot rewrites the
        // intercepted execve, and only handler NAMES travel into the guest.
        //
        // Pass the option only when the server actually bound. In the degraded
        // state passing it would make every offloaded execve attempt a
        // connection that cannot succeed; without it the command is executed
        // normally (and unknown handler names surface as "command not found"
        // instead of an intercept that can never answer).
        if (includeNativeOffload && NativeOffloadServer.isListening) {
            val handlers = NativeOffloadServer.registeredHandlers
            if (handlers.isNotEmpty()) {
                cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
            }
        }
        return cmd
    }
