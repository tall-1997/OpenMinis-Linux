# Android runtime memory changes

Shipped in **2.0.2** (`versionCode` 202). Baseline: `a54d804` (2.0.1). This change addresses resource retention and avoidable allocation peaks identified during review of a user memory/reboot report. The report does not establish an OOM or hardware-failure root cause.

## Behavior

- Heavy shell commands share one application-wide admission mutex. Builds, package managers, JVM/decompiler/compiler commands and ffmpeg are recognized; an opaque script can explicitly request `resource_class: "heavy"`. Classification happens before shell wrapping. Lightweight commands and common process-cleanup commands remain available.
- Before admitting heavy work, inspect ActivityManager available memory and its low-memory signal. The reserve is the larger of the platform threshold and 10% of RAM clamped to 256–1024 MiB. Visible same-UID background JVM/compiler processes also defer new heavy work. Waits are cancellable and bounded; they do not timeout the running holder. These are admission controls, not cgroup or RSS limits. Arbitrary hidden workloads cannot be inferred from every shell program.
- A shell log preview retains at most 50 callback lines and 32 Ki characters. One consumer publishes every 150 ms, with a final flush. A conflated signal channel prevents line-by-line Main coroutine buildup. The process output drain and final command output policy remain separate.
- Browser tabs now have explicit permanent disposal. Close/idle eviction releases WebView resources; owner disposal cancels the independent timer and download scopes and clears callbacks. Suspended agent operations retain their tab until their finally block finishes. Hiding a sheet does not dispose the pool.
- Browser pools retain their existing per-session limit of 3 tabs and share a process-wide limit of 6. Admission can reclaim a cold tab from another non-visible pool; visible pages, busy tabs and pools with active downloads are protected. If no suitable slot exists, no extra WebView is allocated.
- Screenshots and live snapshots are bounded before bitmap allocation: at most 4,000,000 pixels and 4096 pixels per edge. Drawing scales to the bounded bitmap. JPEG encoding happens once on IO, and native bitmap pixels are released before creating Base64. Screenshot output reminds the caller to use viewport coordinates when scaled.
- Retain up to 3 evictable idle chat ViewModels with an estimated text budget of maxHeap/16, clamped to 8–32 MiB. Visible chats, unsaved input/attachments, ongoing jobs, approvals, prompts and browser downloads are pinned. Cleanup is delayed 30 seconds to avoid navigation/configuration churn and retried while over capacity. On memory/UI-hidden callbacks the idle budget can drop to zero. This bounds eligible cached text, not total application PSS.
- Cache eviction preserves independent sandbox shell services and draft-to-canonical aliases. Explicit session deletion still terminates its shell. Re-creation resolves the canonical session ID. Global safe-mode listeners are unregistered when the VM is cleared.

## Verification

Targeted regression classes:

- `ScreenshotBudgetTest`: normal viewport, long/wide/overflow-sized page limits.
- `ShellOutputPreviewTest`: 10,000-line burst coalescing, bounded giant line, final flush, no post-cancel render.
- `IdleSessionBudgetTest`: LRU order, count/byte limits, pinned sessions protected.
- `HeavyTaskAdmissionTest`: cross-tool exclusion, cancellation of holder/waiter, low-memory admission, explicit heavy script and recovery commands.
- Existing `SandboxResourceGateTest`: package-manager lock compatibility and wait behavior.
- `BrowserMemoryLifecycleTest` (instrumentation): close and idempotent owner disposal, visible/busy protection, global budget.

Both the standalone Kotlin/JUnit run and the project's `:app:testDebugUnitTest` run passed all 16 selected tests (zero failures/errors/skips). Full `:app:compileDebugKotlin` passed using a local init script adding `-Xno-optimize`, one worker, in-process Kotlin compilation and a 2560 MiB build JVM heap. The validation-only init script is outside the repository; release/build configuration is unchanged. The default optimizer run was interrupted after sustained large-method optimization, and a 1536 MiB in-process attempt reported heap pressure before retrying with the stated build budget.

The Gradle Android-test compilation task was blocked resolving `androidx.activity:activity:1.9.3` from Google Maven (TLS handshake failure); a China-mirror retry stalled during dependency resolution and was stopped. `BrowserMemoryLifecycleTest` was then compiled successfully in isolation against the real Android 36 SDK, cached AndroidX test monitor library and compiled application classes. The browser instrumentation tests still must run against a built test APK on an Android test device. No production installation or user-data replacement is required to review this patch. Actual PSS reduction and the reported OPPO reboot have not been measured or reproduced by this change.

## Device acceptance

Capture idle / normal workload / post-workload memory with `scripts/collect-memory.sh`, plus `dumpsys meminfo`, MemAvailable, PSI and relevant child-process PSS. Exercise repeated chat navigation (including promoted drafts), long shell output, tab creation/close, closing a tab during navigation, long screenshots, background downloads and cancellation while waiting for the heavy-task budget. Confirm protected work continues and post-workload resident memory plateaus after reclamation. Avoid treating a single MemFree reading or native-allocator counter as total process usage.
