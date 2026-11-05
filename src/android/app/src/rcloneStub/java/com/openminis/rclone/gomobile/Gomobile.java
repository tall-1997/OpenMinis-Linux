package com.openminis.rclone.gomobile;

/**
 * Compile-time stand-in used when {@code app/libs/rclone.aar} is absent.
 * Remote backup destinations will fail at RPC time with a clear message
 * instead of blocking {@code :app:compileDebugKotlin}. Build the real
 * binding with Go + {@code deps/build_rclone_android.sh}.
 *
 * [port-upstream-1.16] Extended with the ts* (embedded Tailscale node /
 * tsnet), net* (nethub local entry, transparent forwarding, diagnostics) and
 * rcloneSetSocks entry points the Settings > Network domain
 * (com.openminis.app.network, ported from OpenMinis/OpenMinis@main) calls on
 * the same Go runtime. The stub answers the same JSON envelope
 * {@code {"ok":false,"error":…}} the real binding uses, so a missing AAR
 * surfaces at call time as a clean error — Settings > Network never takes a
 * build down and never pretends to work.
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

    /** [port-upstream-1.16] SFTP/FTP socks_proxy (rclone-route). */
    public static String rcloneSetSocks(String socks) {
        return stubError("rcloneSetSocks");
    }

    // Embedded Tailscale node (tsnet). Signatures mirror the real gomobile
    // binding; see src/android/app/src/main/java/com/openminis/app/network/
    // TailscaleNode.kt for the envelope contract.

    public static String tsSetLogger(LogSink sink) {
        return stubError("tsSetLogger");
    }

    public static String tsStart(String authKey, String hostname, String stateDir) {
        return stubError("tsStart");
    }

    public static String tsSetAcceptRoutes(boolean accept) {
        return stubError("tsSetAcceptRoutes");
    }

    public static String tsSetExitNode(String ip) {
        return stubError("tsSetExitNode");
    }

    public static String tsStop() {
        return stubError("tsStop");
    }

    public static String tsLogout() {
        return stubError("tsLogout");
    }

    public static String tsStatus() {
        return stubError("tsStatus");
    }

    public static String tsRoutes() {
        return stubError("tsRoutes");
    }

    public static String tsDialTest(String address, long timeoutMs) {
        return stubError("tsDialTest");
    }

    public static String tsSetInterfaces(String interfacesJson) {
        return stubError("tsSetInterfaces");
    }

    public static void tsSetDefaultRouteInterface(String ifName) {
        // no-op — the node is not running in the stub
    }

    public static void tsNetworkChanged() {
        // no-op — the node is not running in the stub
    }

    // Local SOCKS5 entry + transparent forwarding + diagnostics (nethub).

    public static String netConfigure(String configJson) {
        return stubError("netConfigure");
    }

    public static String netStop() {
        return stubError("netStop");
    }

    public static String netHookRoutes(String tag, String localJson) {
        return stubError("netHookRoutes");
    }

    public static String netHookRoutes2(String tag, String localJson, String resolversJson) {
        return stubError("netHookRoutes2");
    }

    public static String netXparentStart(String mapDir, String tag) {
        return stubError("netXparentStart");
    }

    public static String netShellProbe(String resolver, String namesJson, long timeoutMs) {
        return stubError("netShellProbe");
    }

    public static String netXparentStop() {
        return stubError("netXparentStop");
    }

    public static String netXparentStatus() {
        return stubError("netXparentStatus");
    }

    public static String netStatus() {
        return stubError("netStatus");
    }

    public static String netHealth() {
        return stubError("netHealth");
    }

    public static String netSelfTest(long timeoutMs) {
        return stubError("netSelfTest");
    }

    public static String netDiagnose(long timeoutMs) {
        return stubError("netDiagnose");
    }

    public static String netDecide(String tag, String host) {
        return stubError("netDecide");
    }

    public static String netProbe(String tag, String address, long timeoutMs) {
        return stubError("netProbe");
    }

    public static String netTestUpstream(String upstreamJson, String target, long timeoutMs) {
        return stubError("netTestUpstream");
    }

    private static String stubError(String entry) {
        return "{\"ok\":false,\"error\":\"rclone.aar is not built: the Go "
            + "network core (" + entry + ") is unavailable. Build the AAR with "
            + "./deps/build_rclone_android.sh — Settings > Network (Tailscale, "
            + "proxies, shell routing) needs it.\"}";
    }
}
