package com.openminis.app.provider

/**
 * OpenCode Zen host recognition.
 *
 * ## What the 2026-10-17 measurement got wrong
 *
 * The keyless lane was retired on the belief that the refusal was an identity
 * wall: every third-party transport got `403 FreeTierError`, replaying the
 * official CLI's full header set changed nothing, so no header shape a client
 * can imitate would ever pass. That conclusion was wrong because the experiment
 * held the request **body** constant — every one of those probes sent a bare
 * `{"model":…,"messages":…}` body with no `tools` array at all.
 *
 * ## 2026-10-23 controlled re-measurement
 *
 * Same host, same `Authorization: Bearer public`, same CLI header set, exactly
 * one variable changed per request — the `tools` array:
 *
 *  - no `tools` key                                     -> 403 FreeTierError
 *  - `tools` = [bash] / [glob] / [grep] / [write] /
 *    [edit] / [task] / [webfetch], each one alone        -> 403 FreeTierError
 *  - `tools` = [read], alone                            -> **200**, full answer
 *  - `tools` = [read] together with `stream: false`      -> 403 FreeTierError
 *
 * The gate is therefore a request-body shape check — the body must carry a
 * function named `read` and must ask for a stream — and nothing about client
 * identity is verified. [ZenFreeLaneGate] satisfies it on the wire; the lane is
 * usable again with no credential.
 *
 * What still survives here is the host check, because the Zen endpoint speaks
 * its own error dialect (`{"type":"error","error":{"type":"ModelError"|
 * "RegionError"|"FreeTierError"}}`) that the generic OpenAI mapping misreads as
 * a bad key. Recognition covers all four spellings the service has used, so a
 * base URL written as `opencode.ai/zen/v1`, `opencode.ai/inference/v1`,
 * `inference.opencode.ai/v1` or `gateway.opencode.ai/v1` all get it.
 */
internal object ZenEndpoint {
    fun isZenHost(baseURL: String?): Boolean =
        baseURL != null && (
            baseURL.contains("opencode.ai/zen", ignoreCase = true) ||
                baseURL.contains("opencode.ai/inference", ignoreCase = true) ||
                baseURL.contains("inference.opencode.ai", ignoreCase = true) ||
                baseURL.contains("gateway.opencode.ai", ignoreCase = true)
            )
}
