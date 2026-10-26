package com.openminis.app.provider

/**
 * The client identity the keyless Zen lane is impersonated as.
 *
 * [T-zen-client-version] The v2 gate (in force since 2026-10-14) fingerprints
 * the **whole** User-Agent string: the shape is
 * `opencode/<channel>/<version>/<client>` with a major version ≥ 2, and the
 * match is exact — any prefix or suffix on the string stops being "a v2
 * client" and answers `403 FreeTierError`. The measured-good value is
 * `opencode/latest/2.0.26/cli`, taken from the official 2.0.26 CLI and kept
 * verbatim.
 *
 * ## Why this is pinned, not probed
 *
 * Earlier builds resolved the version from `registry.npmjs.org` in the
 * background. That probe is gone on purpose:
 *
 * - the npm channel publishes behind the builds the endpoint fingerprints
 *   (npm reported `1.19.4` as latest while the working client string said
 *   `2.0.29`), so adoption could never be trusted to move the value forward;
 * - an adopted version is by definition a string this build never measured
 *   against the gate, and the v2 gate rejects unmeasured shapes with a 403 —
 *   the exact failure mode this object exists to prevent;
 * - the previous `opencode/prod/<version>/cli` spelling used a channel segment
 *   the official client does not send, which is the same class of drift.
 *
 * When upstream moves again, the change is a one-line edit here plus a
 * re-measurement — not a runtime guess.
 */
internal object ZenClientVersion {
    /** Measured-good v2 client string; sent verbatim, never reassembled. */
    const val USER_AGENT = "opencode/latest/2.0.26/cli"

    fun userAgent(): String = USER_AGENT
}
