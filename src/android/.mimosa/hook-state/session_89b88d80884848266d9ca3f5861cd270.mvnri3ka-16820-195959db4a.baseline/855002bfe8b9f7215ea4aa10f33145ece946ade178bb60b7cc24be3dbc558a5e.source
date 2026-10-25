package com.openminis.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-rm-root-false-positive] Deletion-target classification.
 *
 * The bug this pins: `contains("rm -rf /")` cannot tell "wipe the root" from
 * "wipe something whose path starts with a slash" — which is every absolute
 * path. It hard-denied routine cleanups (`rm -rf /sdcard/Download/tmp`,
 * `rm -rf /tmp/x; echo done`) with exit 126 in every permission mode, and it
 * even refused the app's own notification CLEANUP action, whose script wipes
 * the `/tmp` and `/var/tmp` globs. It also fired on commands that merely *mentioned*
 * the string, so `grep -rn 'rm -rf /' src/` was denied for containing its own
 * search pattern.
 *
 * Both directions are asserted. The "must stay refused" half is what keeps the
 * fix from becoming a bypass: wrappers (`sudo`, `busybox`, `env`, `timeout`,
 * `nice`, `xargs`, `find -exec`), shell payloads (`su -c`, `sh -c`), the `--`
 * operand separator and multi-target forms all still resolve to the root.
 */
class RmTargetPolicyTest {

    @Test
    fun rootDeletionIsRefusedInEverySpelling() {
        for (command in listOf(
            "rm -rf /",
            "rm -fr /",
            "rm -r -f /",
            "rm -rf --recursive --force /",
            "rm -rf --no-preserve-root /",
            "rm -rf / ",
            "rm -rf /*",
            "rm -rf /.",
            "rm -rf /..",
            "rm -rf -- /",
            "rm -rf /tmp/x /",
            "/bin/rm -rf /",
            "rm -rf / 2>/dev/null",
        )) {
            assertTrue("must stay refused: $command", rmTargetsRoot(command))
        }
    }

    @Test
    fun rootDeletionIsRefusedThroughWrappersAndShellPayloads() {
        for (command in listOf(
            "sudo rm -rf /",
            "sudo -u root rm -rf /",
            "busybox rm -rf /",
            "env X=1 rm -rf /",
            "timeout 5 rm -rf /",
            "nice -n 19 rm -rf /",
            "stdbuf -oL rm -rf /",
            "xargs rm -rf /",
            "nohup rm -rf /",
            "find . -exec rm -rf / \\;",
            "su -c \"rm -rf /\"",
            "sh -c 'rm -rf /'",
            "bash -c \"sudo rm -rf /\"",
            "echo hi; rm -rf /",
            "cd /tmp && rm -rf /",
            "ls || rm -rf /",
        )) {
            assertTrue("must stay refused: $command", rmTargetsRoot(command))
        }
    }

    @Test
    fun legitimateAbsolutePathsAreNotRootDeletion() {
        for (command in listOf(
            // The commands that were actually denied in the field.
            "rm -rf /sdcard/Download/dbcheck",
            "rm -rf /tmp/x; echo done",
            "rm -rf /sdcard/Download/dbcheck && rm -f /sdcard/Download/base.apk",
            "cd /sdcard/Download && rm -rf /sdcard/Download/tmp",
            // The app's own notification CLEANUP script.
            "rm -rf /tmp/* /var/tmp/* 2>/dev/null || true",
            "rm -rf /var/minis/workspace/build",
            "rm -rf /data/local/tmp/probe",
            "rm -f /var/minis/attachments/a.png",
            // Prefix lookalikes: not /system, not /data.
            "rm -rf /system_backup",
            "rm -rf /database",
            "rm -rf /production-assets",
            // Relative paths and unresolvable operands.
            "rm -rf ./build",
            "rm -rf build dist",
            "rm -rf \"\$DIR\"",
        )) {
            assertFalse("must be allowed: $command", rmTargetsRoot(command))
        }
    }

    @Test
    fun commandsThatMerelyMentionTheStringAreNotRootDeletion() {
        for (command in listOf(
            "grep -rn 'rm -rf /' src/",
            "echo \"rm -rf /\"",
            "cat README.md | grep 'rm -rf /'",
            "echo 'never run rm -rf / on the host'",
        )) {
            assertFalse("must be allowed: $command", rmTargetsRoot(command))
        }
    }

    @Test
    fun dataPartitionDeletionMatchesOnlyWholesaleTargets() {
        for (command in listOf(
            "rm -rf /data",
            "rm -rf /data/",
            "rm -rf /data/*",
            "sudo rm -rf /data",
        )) {
            assertTrue("must match /data wholesale: $command", rmDeletesPath(command, "/data"))
        }
        for (command in listOf(
            // Underneath /data — the carve-outs the old substring version had to
            // spell out by hand are now implied by "wholesale only".
            "rm -rf /data/data/com.openminis.linux/cache",
            "rm -rf /data/local/tmp",
            "rm -rf /database",
            "rm -rf /sdcard/data",
            "rm -f /data/backup.db",
        )) {
            assertFalse("must not match /data wholesale: $command", rmDeletesPath(command, "/data"))
        }
    }

    @Test
    fun nonRmCommandsAreNeverReported() {
        for (command in listOf(
            "ls /",
            "find / -name '*.db'",
            "du -sh /",
            "tar -czf /tmp/backup.tgz -C / .",
            "mv /tmp/a /tmp/b",
        )) {
            assertFalse("not an rm invocation: $command", rmTargetsRoot(command))
        }
    }
}
