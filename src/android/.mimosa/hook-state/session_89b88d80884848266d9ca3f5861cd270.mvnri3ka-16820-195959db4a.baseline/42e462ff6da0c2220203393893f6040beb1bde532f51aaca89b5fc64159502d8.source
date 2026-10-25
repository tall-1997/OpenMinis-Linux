package com.openminis.rclone.gomobile;

/**
 * Compile-time stand-in used when {@code app/libs/rclone.aar} is absent.
 * Remote backup destinations will fail at RPC time with a clear message
 * instead of blocking {@code :app:compileDebugKotlin}. Build the real
 * binding with Go + {@code deps/build_rclone_android.sh}.
 */
public final class Gomobile {
    private Gomobile() {}

    public static void rcloneInitialize() {
        // no-op — RPC returns 501
    }

    public static void rcloneFinalize() {}

    public static RcloneRPCResult rcloneRPC(String method, String input) {
        RcloneRPCResult r = new RcloneRPCResult();
        r.setStatus(501);
        r.setOutput(
            "{\"error\":\"rclone.aar is not built. Host needs Go 1.22+ and "
                + "gomobile; then run ./deps/build_rclone_android.sh and copy "
                + "the AAR to src/android/app/libs/rclone.aar. First APK "
                + "builds do not require this — only SMB/WebDAV/SFTP/S3/FTP "
                + "backup destinations do.\"}"
        );
        return r;
    }
}
