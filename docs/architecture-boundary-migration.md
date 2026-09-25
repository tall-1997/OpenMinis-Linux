# Android architecture boundary migration

## Dependency graph

```text
:app
 ├─ :core:model
 ├─ :core:common
 ├─ :provider:api ──> :core:model
 └─ :hostcapability:api

build-logic (included build)
 └─ minis.kotlin.library
```

The application module owns Android implementations and UI. API modules contain no Android dependencies. Existing package names are retained so the migration does not change serialized data, JNI names, MCP/offload JSON, or public Kotlin call sites.

## Application assembly

`MinisApp` creates `AppCoroutineScopes` and `AppContainer` through `AppGraph`. Repositories are assembled in the original order and exposed through compatibility accessors. `subsystemsInitialized`, `subsystemsReady()`, `chatRepositoryOrNull`, and `providerRepositoryOrNull` preserve degraded/safe-mode behavior.

## Provider boundary

`provider/api` defines `ModelProvider`, request/response/stream aliases, capabilities, normalized provider exceptions, and a registry. `LLMProvider` implements `ModelProvider` through default bridges, so OpenAI, Anthropic, Gemini, OpenRouter, and compatible providers share one contract without changing their HTTP payloads or retry behavior.

## Host-capability boundary

`hostcapability/api` defines request, response, permission, error, handler, and registry contracts. `NativeOffloadAdapter` remains in `:app` and preserves argv/env/cwd/session/exit-code behavior while adding structured timeout and cancellation.

## Coroutine ownership

Application work uses `AppCoroutineScopes`. `GlobalScope` has been removed. Receiver handoffs use the application scope while retaining `goAsync().finish()`. Session wait diagnostics are structured children of the waiting coroutine. Object/service-owned scopes remain where the owner exposes cancellation or has a process lifetime; Compose uses `rememberCoroutineScope`.

## CI artifact chain

```text
native-deps.yml ───────┐
                       ├─> android-apk.yml ─> APK + symbols + releases
sandbox-assets.yml ────┘
```

The APK job no longer installs Go, invokes `gomobile init`, builds PRoot, builds libunwind, or downloads sandbox payloads. Those operations run in prerequisite reusable workflows and are transferred as immutable per-commit artifacts. All sandbox resources remain embedded in the APK.

## Validation status

- `GlobalScope`: zero matches.
- Business `lateinit var`: zero; only two Android Framework `ActivityResultLauncher` fields remain.
- `:app:compileDebugKotlin`: passed after each migration commit.
- `:app:mergeReleaseAssets`: passed.
- Bundled model catalog focused test: passed.
- Full unit suite: 1663 tests, 26 known pre-existing failures; no new failing test names or locations relative to the pre-migration baseline.
- Local assemble/lint: blocked at native configure because the Windows NDK sysroot lacks generated `aarch64 libunwind.a`; CI supplies it through `native-deps.yml`.
- Versioned releases `1.36.52-linux` and `1.36.53-linux`: repaired and published with two assets each.

## Remaining migration policy

Additional feature/data/native implementation extraction should follow the same rule: move one acyclic boundary per commit, preserve package and wire contracts, compile after every move, and never externalize required APK assets.
