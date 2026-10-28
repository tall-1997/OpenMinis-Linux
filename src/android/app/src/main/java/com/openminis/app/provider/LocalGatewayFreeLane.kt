package com.openminis.app.provider

/**
 * The Our-Free-Model local gateway lane: an OpenAI-compatible proxy running
 * inside the PRoot sandbox (`http://127.0.0.1:18900/v1`), installed from the
 * APK's ofm builtin-plugin payload. All lane semantics are shared with every
 * other loopback gateway — see [LoopbackGatewayLane]; this object is the
 * identity (lane id, port 18900, the labels the config layer shows).
 */
internal object LocalGatewayFreeLane : LoopbackGatewayLane(
    laneId = FreeLanes.OFM_ID,
    port = 18900,
    providerId = "ofm-gateway",
    displayName = "Free Model Gateway (Local)",
    providerLabel = "OFM",
) {
    const val HOST = LoopbackGatewayLane.HOST
    const val PORT = 18900
    const val BASE_URL = "http://127.0.0.1:18900/v1"
    const val PROVIDER_ID = "ofm-gateway"
    const val DISPLAY_NAME = "Free Model Gateway (Local)"
}
