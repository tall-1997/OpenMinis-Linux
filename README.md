<p align="right"><b>English</b> · <a href="README.zh-CN.md">中文</a></p>

# Minis Ultra

<p>
  <a href="https://github.com/tall-1997/OpenMinis-Linux/releases"><img alt="Release" src="https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases"></a>
  <a href="LICENSE"><img alt="License: GPL v3" src="https://img.shields.io/badge/License-GPLv3-blue.svg"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Android-arm64-3ddc84">
  <img alt="Guest" src="https://img.shields.io/badge/Guest-Ubuntu%2024.04-E95420">
</p>

**One phone, one AI that can actually do things.**

Minis Ultra is a local, private AI assistant running on Android arm64. It doesn't just chat — your phone carries a complete Ubuntu 24.04 Linux sandbox plus a device-automation layer, so the model can install software, write code, run scripts, operate apps, and change system settings, all on your own device.

> No root required. Nothing is uploaded to any third-party server — except the model endpoint you configure yourself.

## Three execution environments, one session

The model moves freely between three ways of "doing things" inside a single session:

```
┌─────────────────────────────────────────────────┐
│                Minis Ultra session               │
├────────────────┬────────────────┬───────────────┤
│  Linux sandbox │ Device automation │ Embedded browser │
│  Ubuntu 24.04  │  30 structured   │  Desktop and    │
│  user-space    │  tools driving   │  mobile profiles│
│  apt / shell / │  the phone       │  with per-      │
│  file system   │  (Shizuku-       │  session cache  │
│                │  enhanced)       │                 │
└────────────────┴────────────────┴───────────────┘
```

- **Linux sandbox** — an Ubuntu 24.04 arm64 guest running under PRoot in user space, with its own package manager, shell, and file system (details in [LINUX.md](LINUX.md)). Sessions in a project share the workspace; sessions without one get their own. Session diaries are always private.
- **Device automation** — 30 structured tools: system settings (Wi-Fi, Bluetooth, volume, brightness, Do Not Disturb, flashlight, alarms), app launch and deep links, notification read / reply / dismiss, contacts / SMS / call log / calendar, media control, clipboard, and location. Capabilities are automatically enhanced when [Shizuku](https://github.com/RikkaApps/Shizuku) is present; every tool has a documented fallback without it.
- **Dedicated virtual display** — the agent operates apps on its own screen. Login, captcha, and payment pages are handed back to you automatically, then resumed afterwards.

## What that looks like

- 🛠️ "Install Node and get this repo running" → apt + git + npm, end to end, inside the sandbox
- 📱 "Read the verification code from WeChat and type it into the browser" → notification reading plus automated on-screen operation
- ⏰ "Every morning at 8, turn yesterday's calendar into a checklist and send it to me" → scheduled automation rules running unattended
- 🔌 "Hook up my local database tool" → the MCP client connects to any tool server

## Multi-agent and automation

- The **main session dispatches sub-agents**, background jobs, and interactive PTY terminals in parallel.
- **Automation rules**: time schedules plus Wi-Fi / Bluetooth connection, charging, battery level, screen state, notification, and app-foreground triggers — running unattended under a per-rule tool whitelist.
- **Guardrails**: repeated identical actions, ineffective actions, and failure streaks are warned and then stopped; outward-facing actions (sending, submitting) are automatically paced.
- **Result cards**: tool results users consume render as typed cards, and state changes carry a one-tap undo that re-executes the original tool with the previous value.
- **App knowledge**: bundled operating guides for 16 common applications, with on-device experience notes the agent maintains per app.

## Connectivity and local services

- Any **OpenAI-compatible endpoint**; models missing published limits are completed from the models catalog (context window, output cap, reasoning effort).
- **MCP client and server**, global skills, and per-session memory diaries under `/var/minis/memory`.
- **Quick invocation**: a floating ball, a quick-settings tile, the system share sheet, and a home-screen widget all open a half-screen command panel.

## Download

[Releases](https://github.com/tall-1997/OpenMinis-Linux/releases) publish `minis-ultra-com.openminis.linux.apk`:

- **versioned tags** (for example `2.0.90-linux`);
- a rolling build, [android-latest](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest), replaced on each successful `main` build.

Allow unknown-app installs before sideloading. A debug-signed build cannot replace an installation signed with a different certificate; signing notes are in [BUILDING.md](BUILDING.md).

Package id `com.openminis.linux`, launcher name **Minis Ultra**. It installs alongside upstream [OpenMinis](https://github.com/OpenMinis/OpenMinis) (GPL-3.0); update checks point only at this repository.

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

## License

This repository is published under the [GNU GPL v3.0](LICENSE). It contains and modifies GPL-3 programs, so derivative works must ship corresponding source under GPL-3. The sandbox links [PRoot](https://github.com/proot-me/PRoot/) (GPL-2.0); the combined work is distributed under GPL-3.

The authoritative component list, including versions and licenses, is [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). Summary:

**Native (`deps/`)**

| Component | License | Role |
|---|---|---|
| [proot](https://github.com/OpenMinis/proot) (fork) | GPL-2.0 | Linux sandbox (`deps/proot` submodule) |
| [talloc](https://talloc.samba.org) | LGPL-3.0-or-later | Allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | MIT | Chinese word segmentation |
| Ubuntu 24.04 (noble) arm64 rootfs | Canonical / Ubuntu package licenses | PRoot guest, downloaded at build time |
| [android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) (aarch64) and Android SDK command-line tools | Apache-2.0 (AOSP) | aapt2 / zipalign / adb / sdkmanager inside the guest |

**Android (Gradle)**: AndroidX / Jetpack, OkHttp, kotlinx-coroutines, kotlinx-serialization-json, Coil, markdown-renderer, Reorderable, ACRA (Apache-2.0); [Shizuku](https://github.com/RikkaApps/Shizuku) (MIT); Mozilla Rhino (MPL-2.0)

**Bundled**: KaTeX and jieba dictionaries (MIT)

**Adapted source**: selected Android agent components adapted from [XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public) (GPL-3.0-or-later) — see THIRD_PARTY_LICENSES.md for the component list.

Test-only dependencies (JUnit 4, MockWebServer, kotlinx-coroutines-test, org.json) are listed in THIRD_PARTY_LICENSES.md.

## Issues

Bug reports and feature requests go to [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues).
