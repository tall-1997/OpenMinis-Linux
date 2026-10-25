package com.openminis.app.ssh

import java.io.File

/**
 * [T-ssh-backend] High-level surface of the SSH backend.
 *
 * One implementation ([JschSshBackend]) talks to real servers; tests inject
 * fakes so SshTool routing/formatting is pure-JVM verifiable. All methods
 * are blocking — callers must run them on an IO dispatcher.
 *
 * Design notes (mirrors aicode's RemoteSshEngine split):
 * - exec and SFTP run on SEPARATE channels of one pooled session, so a
 *   long-running command never blocks a transfer (SSH channel multiplexing).
 * - Host keys are TOFU-pinned via [SshKnownHosts]; a changed key aborts the
 *   handshake before credentials are offered.
 * - Secrets never leave [SshConfigStore]'s encrypted box except as transient
 *   in-memory credential bytes handed to the transport.
 */
interface SshBackend {
    val store: SshConfigStore
    val knownHosts: SshKnownHosts

    /**
     * Connect + authenticate (no command). Returns a one-line summary:
     * server version and pinned host key fingerprint. Throws [SshFailure].
     */
    fun test(hostRef: String): String

    /**
     * Run [command] on the remote host. [cwd] is wrapped as a quoted
     * `cd '<cwd>' && …` prefix. Never throws for a non-zero exit code —
     * that is data ([SshExecResult.exitCode]); transport problems throw
     * [SshFailure]. On timeout the partial output is returned with
     * [SshExecResult.timedOut] set.
     */
    fun exec(hostRef: String, command: String, timeoutMs: Long, cwd: String?): SshExecResult

    /** Remote directory listing via SFTP. */
    fun ls(hostRef: String, path: String): List<SshLsEntry>

    /** Download remotePath → localFile (host-side File). Returns bytes written. */
    fun get(hostRef: String, remotePath: String, localFile: File): Long

    /** Upload localFile (host-side File) → remotePath. Returns bytes sent. */
    fun put(hostRef: String, localFile: File, remotePath: String): Long

    /** Drop the TOFU pin for this host so the next connect re-pins. Evicts any pooled session. */
    fun forgetHostKey(hostRef: String)

    /**
     * Remove a host config AND its stored credentials, evicting any pooled
     * session first — a removed host must not keep working through a live
     * connection. Returns false when [hostRef] matched nothing.
     */
    fun removeHost(hostRef: String): Boolean

    /** Disconnect every pooled session. */
    fun close()
}
