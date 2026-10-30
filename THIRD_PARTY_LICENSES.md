# 第三方许可

本仓库（Minis Ultra）安装包实际链接或在构建时下载的第三方组件如下。本安装包不包含 iOS 应用，也不链接 iSH、FFmpeg 或 Alpine rootfs。

## Native C/C++ dependencies (`deps/`)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [proot](https://github.com/OpenMinis/proot) (fork) | git submodule `deps/proot` | **GPL-2.0** | Linux sandbox on Android (`libproot.so`, `proot-aarch64`) |
| [talloc](https://talloc.samba.org) (Samba) | vendored at `deps/talloc` | **LGPL-3.0-or-later** | Memory allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | Android `jieba_jni` | **MIT** | 中文分词 |
| Ubuntu 24.04 base (noble arm64) | downloaded by `scripts/prepare_android_sandbox.sh` | Canonical / Ubuntu package licenses (glibc **LGPL-2.1**, apt **GPL-2.0**, etc.) | Android PRoot guest; not stored in this repo |
| [android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) aarch64 (aapt2, zipalign, adb, aidl) | 35.0.2 static | **Apache-2.0** (AOSP) | Vendored as `assets/android-sdk-tools-aarch64.zip`; unpacked to `/opt/android-sdk` |
| Android SDK Command-line Tools (`sdkmanager`) | 12.0 (`commandlinetools-linux-11076708`) | **Apache-2.0** (Google / AOSP) | Slimmed to the sdkmanager classpath as `assets/android-cmdline-tools.zip`; lint/R8/kotlin-compiler omitted |

## Android — Gradle dependencies

| Library | Version | License |
|---|---|---|
| AndroidX / Jetpack (Compose BOM 2025.09.00, core-ktx, lifecycle, activity, navigation, Room, DataStore, security-crypto, browser, webkit, exifinterface) | see `app/build.gradle.kts` | **Apache-2.0** (Google / AOSP) |
| OkHttp + okhttp-sse | 4.12.0 | **Apache-2.0** |
| kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| kotlinx-coroutines-android | 1.9.0 | **Apache-2.0** |
| Coil (coil-compose) | 2.7.0 | **Apache-2.0** |
| multiplatform-markdown-renderer (+ m3) — mikepenz | 0.33.0 | **Apache-2.0** |
| Reorderable (sh.calvin.reorderable) | 2.4.0 | **Apache-2.0** |
| ACRA (acra-core) | 5.12.0 | **Apache-2.0** |
| Shizuku API + provider (dev.rikka.shizuku) | 13.1.5 | **MIT** |
| Mozilla Rhino (`org.mozilla:rhino`) | 1.7.14 | **MPL-2.0** |

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**).

## Bundled gateway — a2a / Agent2API

a2a 功能随包分发上游 Agent2API 的 web 面板与网关二进制；源码不 vendor 进本仓库，CI 按 `v3.0.8`（commit `68d0a4e4`）拉取构建，tag 漂移会直接失败。

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [Agent2API](https://github.com/aimod-cc/Agent2API) web UI | `v3.0.8` | **MIT** | `app/src/main/assets/builtin_plugins/a2a-ui.zip`，首次启用时解到 app 私有目录 |
| Agent2API gateway (`agent2api-server`) | `v3.0.8` | **MIT** | CI 交叉编译为 `jniLibs/arm64-v8a/liba2a.so`，由 app 进程托管运行（不进 PRoot） |

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | Android `assets/jieba/` | **MIT**（随 cppjieba 分发） |

## Removed / historical

- **swift-markdown-ui** (MIT) — formerly vendored under `deps/swift-markdown-ui`; no longer referenced by the Xcode project or imported by any source file, and is not part of the open-source tree.
- **dsh-our-free-model (OFM) 内置网关** (MIT) — 2.0.70 及更早版本以 `assets/builtin_plugins/ofm-runtime.zip` 随包分发并在运行时拉取 Node；2.0.80 起连同内置插件层整体移除，安装包不再包含该 payload，也不再下载 Node。

## Adapted Kotlin (not vendored as a module)

| Component | License | Notes |
|---|---|---|
| [XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public) | **GPL-3.0-or-later** | Selected Android agent pieces adapted into OpenMinis: `cronjob`/`CronScheduler`, online OpenAPI plugin registry + `OnlineApiTool`, `CollabRoles` from PresetTeam, `SecurityGate`/`PermissionIntersection`/`ShellArgv`, `dispatch_agents`/`wolfpack_run`/`agent_plan`/`execute_code` (Rhino)/`invoke_skill`/`skill_manage`/`ask_reasoning`, `list_dir`/`glob`/`grep`/`web_fetch`/`multi_edit`, SubAgentTypeStore. Memory ranking stays on OpenMinis `MemoryRecallEngine`. Not a wholesale copy of the XINCODE app. |
