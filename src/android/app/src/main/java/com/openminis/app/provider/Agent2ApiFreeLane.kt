package com.openminis.app.provider

/**
 * The Agent2API local gateway lane: a multi-provider OpenAI-compatible proxy
 * running as a host app process (`http://127.0.0.1:18901/v1`), shipped as
 * the APK's own native `liba2a.so` and managed by the a2a feature's own
 * package (`com.openminis.app.a2a` — see [com.openminis.app.a2a.A2aGateway]),
 * which also serves the account-management web panel on the same port.
 *
 * All lane semantics are shared with every other loopback gateway — see
 * [LoopbackGatewayLane]; this object is the identity (lane id, port 18901,
 * the labels the config layer shows). The models listing is empty until the
 * user adds an upstream account in the panel, and that is fine: an empty
 * listing is the honest state, not a dead lane.
 */
internal object Agent2ApiFreeLane : LoopbackGatewayLane(
    laneId = FreeLanes.A2A_ID,
    port = 18901,
    providerId = "a2a-gateway",
    displayName = "Agent2API Gateway (Local)",
    providerLabel = "A2A",
) {
    const val HOST = LoopbackGatewayLane.HOST
    const val PORT = 18901
    const val BASE_URL = "http://127.0.0.1:18901/v1"
    const val PROVIDER_ID = "a2a-gateway"
    const val DISPLAY_NAME = "Agent2API Gateway (Local)"
}
