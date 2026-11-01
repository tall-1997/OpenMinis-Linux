<p align="right"><b>English</b> · <a href="README.zh-CN.md">中文</a></p>

# Minis Ultra

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android%20arm64-lightgrey.svg)](#download)
[![Release](https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases)](https://github.com/tall-1997/OpenMinis-Linux/releases)

Minis Ultra is a private AI agent for Android arm64. It combines two execution environments in one application:

- a **Linux sandbox** — an Ubuntu 24.04 (noble) arm64 guest running under PRoot in user space, with its own package manager, shell, and file system; and
- a **device automation layer** — 30 structured tools that read the screen, operate apps, and change system settings, with a dedicated virtual display so the agent works on its own screen.

The agent connects to any OpenAI-compatible endpoint. Multi-agent dispatch, scheduled and condition-triggered automation, an embedded browser, MCP client/server, and a bundled Agent2API gateway run locally on the device.

Package id `com.openminis.linux`, launcher name **Minis Ultra**. It installs alongside upstream [OpenMinis](https://github.com/OpenMinis/OpenMinis) (GPL-3.0); update checks point only at this repository.

## License

This repository is published under the [GNU GPL v3.0](LICENSE). It contains and modifies GPL-3 programs, so derivative works must ship corresponding source under GPL-3. The sandbox links [PRoot](https://github.com/proot-me/PRoot/) (GPL-2.0); the combined work is distributed under GPL-3. See [Third-party components](#third-party-components) below and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for the complete, authoritative list.

## Download

Current builds are published on [Releases](https://github.com/tall-1997/OpenMinis-Linux/releases) as `minis-ultra-com.openminis.linux.apk`:

- versioned tags (for example `2.0.82-linux`);
- a rolling build, [android-latest](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest), replaced on each successful `main` build.

Allow unknown-app installs before sideloading. A debug-signed build cannot replace an installation signed with a different certificate; signing notes are in [BUILDING.md](BUILDING.md).

## Features

**Linux sandbox** (details in [LINUX.md](LINUX.md))

- Ubuntu 24.04 arm64 guest under PRoot in user space; no root required.
- Sessions without a project get their own workspace; sessions in a project share the workspace, attachments, and browser cache. Session diaries are always private.
- Model endpoints are OpenAI-compatible; models missing published limits are completed from the models catalog (context window, output cap, reasoning effort).
- The main session dispatches sub-agents, background jobs, and interactive PTY terminals.

**Device automation**

- 30 device tools: system settings (Wi-Fi, Bluetooth, volume, brightness, Do Not Disturb, flashlight, alarms), app launch and deep links, notification read / reply / dismiss, contacts / SMS / call log / calendar, media control, clipboard, and location. Several are enhanced by [Shizuku](https://github.com/RikkaApps/Shizuku) when present; every tool has a documented fallback without it.
- Virtual display: the agent operates apps on its own screen. When a login, captcha, or payment page appears, the screen is handed to the user and returned afterwards.
- Automation rules: time schedules plus Wi-Fi / Bluetooth connection, charging, battery level, screen state, notification, and app-foreground triggers, run unattended under a per-rule tool whitelist.
- Guardrails: repeated identical actions, ineffective actions, and failure streaks are warned and then stopped; outward-facing actions (sending, submitting) are automatically paced.

**Assistant layer**

- Result cards: tool results that users consume (status, lists, media, files, events, state changes, links) render as typed cards; state changes carry a one-tap undo that re-executes the original tool with the previous value.
- App knowledge: bundled operating guides for 16 common applications are injected on first entry, and the agent maintains on-device experience notes per app.
- Agent2API gateway: the upstream [Agent2API](https://github.com/aimod-cc/Agent2API) engine (pinned `v3.0.8`) runs in an app-hosted process, aggregating provider accounts behind an OpenAI-compatible API with the full management panel on-device.
- Quick invocation: a floating ball, a quick-settings tile, the system share sheet, and a home-screen widget all open a half-screen command panel.
- Embedded browser (desktop and mobile profiles), MCP client and server, global skills, and per-session memory diaries under `/var/minis/memory`.

## Build from source

PRoot and the Ubuntu rootfs are produced at build time and are not stored in git. NDK r29 (`29.0.14206865`) is required. Full steps are in [BUILDING.md](BUILDING.md).

```sh
git clone https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
git submodule update --init deps/proot
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleRelease
```

Notes:

- `deps/proot` is the only submodule the Android build needs; `deps/ish` is a legacy iOS submodule that is not compiled.
- Only the release variant exists — `debug` variants are disabled in `app/build.gradle.kts`.
- Do not replace the guest's aarch64 `aapt2` with the sdkmanager x86_64 package. Network mirrors are provided by the built-in `android-sdk-mirrors` skill.

## Repository layout

```
src/android/    Android application (Kotlin / Compose) and JNI
src/shared/     Shared resources
deps/           Native dependency build scripts
docs/           Notes and development documents
release-notes/  Per-version release notes
scripts/        Rootfs and development scripts
```

## Third-party components

The authoritative list, including versions and licenses, is [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). Summary:

**Native (`deps/`)**

| Component | License | Role |
|---|---|---|
| [proot](https://github.com/OpenMinis/proot) (fork) | GPL-2.0 | Linux sandbox (`deps/proot` submodule) |
| [talloc](https://talloc.samba.org) | LGPL-3.0-or-later | Allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | MIT | Chinese word segmentation |
| Ubuntu 24.04 (noble) arm64 rootfs | Canonical / Ubuntu package licenses | PRoot guest, downloaded at build time |
| [android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) (aarch64) and Android SDK command-line tools | Apache-2.0 (AOSP) | aapt2 / zipalign / adb / sdkmanager inside the guest |

**Android (Gradle)**

| Component | License |
|---|---|
| AndroidX / Jetpack (Compose, Room, Navigation, DataStore, security-crypto, webkit, and related) | Apache-2.0 |
| OkHttp + okhttp-sse, kotlinx-coroutines, kotlinx-serialization-json, Coil, mikepenz multiplatform-markdown-renderer, Reorderable, ACRA | Apache-2.0 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) API + provider | MIT |
| Mozilla Rhino | MPL-2.0 |

**Bundled**

| Component | License |
|---|---|
| [Agent2API](https://github.com/aimod-cc/Agent2API) gateway and web UI (pinned `v3.0.8`, CI-built) | MIT |
| KaTeX, jieba dictionaries | MIT |

**Adapted source**: selected Android agent components adapted from [XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public) (GPL-3.0-or-later) — see THIRD_PARTY_LICENSES.md for the component list.

Test-only dependencies (JUnit 4, MockWebServer, kotlinx-coroutines-test, org.json) are listed in THIRD_PARTY_LICENSES.md.

## Issues

Bug reports and feature requests go to [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues).
