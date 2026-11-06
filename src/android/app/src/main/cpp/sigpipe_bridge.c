// [T-shell-stall-yes-head] Minimal JNI bridge so Kotlin's SpawnSignalMask can
// spawn ProcessBuilder children with SIGPIPE unblocked. ART blocks SIGPIPE in
// every app thread and ProcessBuilder children inherit the spawning thread's
// mask across execve, so a guest writer whose reader exited got EPIPE instead
// of the kill it needs (busybox `yes | head` then spins forever). The native
// functions live here, separate from the other JNI libraries, so loading them
// has no dependency on the PTY or text-segmenter stacks.
//
// Port of the two signal-mask functions of upstream OpenMinis pty_bridge.c.
#include <jni.h>
#include <signal.h>
#include <pthread.h>
#include <time.h>
#include <android/log.h>

// Unblocks SIGPIPE on the calling thread. Returns a bit set:
//   1 = SIGPIPE was blocked before (caller must re-block),
//   2 = SIGPIPE's disposition is SIG_IGN (a child inherits that too, so
//       unblocking alone does not help - logged by the caller).
// -errno on failure.
//
// [T-sigpipe-pending-drain] A SIGPIPE already PENDING on this thread is
// consumed first. The thread is a shared IO-dispatcher thread: an earlier
// write of its own to a broken pipe or socket left SIGPIPE pending while
// blocked. Unblocking would deliver it at once with the default disposition,
// killing the app with no exception and no tombstone. A zero-timeout
// sigtimedwait takes it off the pending set while still blocked; this thread
// makes no write in the window, so none can arrive before the re-block.
JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_SpawnSignalMask_nativeUnblockSigpipe(
    JNIEnv *env, jclass clazz)
{
    sigset_t set, old, pending;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    if (sigpending(&pending) == 0 && sigismember(&pending, SIGPIPE) == 1) {
        struct timespec zero = { 0, 0 };
        int drained = 0;
        while (drained < 8 && sigtimedwait(&set, NULL, &zero) == SIGPIPE) drained++;
        __android_log_print(ANDROID_LOG_WARN, "SpawnSignalMask",
            "[T-sigpipe-pending-drain] drained %d pending SIGPIPE before unblock", drained);
    }
    int rc = pthread_sigmask(SIG_UNBLOCK, &set, &old);
    if (rc != 0) return -rc;
    jint result = sigismember(&old, SIGPIPE) == 1 ? 1 : 0;
    struct sigaction sa;
    if (sigaction(SIGPIPE, NULL, &sa) == 0 && sa.sa_handler == SIG_IGN) result |= 2;
    return result;
}

// [T-shell-stall-yes-head] Re-block SIGPIPE on the calling thread. 0 or -errno.
JNIEXPORT jint JNICALL
Java_com_openminis_app_sandbox_SpawnSignalMask_nativeBlockSigpipe(
    JNIEnv *env, jclass clazz)
{
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, SIGPIPE);
    int rc = pthread_sigmask(SIG_BLOCK, &set, NULL);
    return rc == 0 ? 0 : -rc;
}
