<p align="right"><a href="README.md">English</a> · <b>中文</b></p>

# Minis Ultra

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android%20arm64-lightgrey.svg)](#下载)
[![Release](https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases)](https://github.com/tall-1997/OpenMinis-Linux/releases)

Minis Ultra 是面向 Android arm64 的私人 AI Agent，在一个应用内组合两类执行环境：

- **Linux 沙箱**——Ubuntu 24.04 (noble) arm64 客户机，以 PRoot 跑在用户态，带独立的包管理器、shell 与文件系统；
- **设备自动化层**——30 个结构化工具，读写屏幕、操作应用、修改系统设置，并配有独立虚拟屏让 Agent 在自己的屏幕上工作。

Agent 连接任意 OpenAI 兼容接口。多智能体调度、定时与条件触发自动化、内置浏览器、MCP 客户端/服务端，以及内置 Agent2API 网关全部在本地运行。

包名 `com.openminis.linux`，启动器名称 **Minis Ultra**。可与上游 [OpenMinis](https://github.com/OpenMinis/OpenMinis)（GPL-3.0）并排安装；检查更新只指向本仓库。

## 许可

本仓库以 [GNU GPL v3.0](LICENSE) 发布。它包含并修改了 GPL-3 程序，因此衍生作品必须按 GPL-3 提供对应源码。沙箱链接 [PRoot](https://github.com/proot-me/PRoot/)（GPL-2.0），合并分发按 GPL-3 处理。完整的第三方组件清单见下文[第三方组件](#第三方组件)与 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 下载

当前版本发布在 [Releases](https://github.com/tall-1997/OpenMinis-Linux/releases)，安装包名为 `minis-ultra-com.openminis.linux.apk`：

- 正式版：带版本号的 tag（例如 `2.0.82-linux`）；
- 滚动构建：[android-latest](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)，`main` 每次成功构建覆盖。

侧载前允许「安装未知应用」。debug 签名不能覆盖另一张证书装上的版本，签名说明见 [BUILDING.md](BUILDING.md) 的「Release 签名」一节。

## 功能

**Linux 沙箱**（详见 [LINUX.md](LINUX.md)）

- Ubuntu 24.04 arm64 客户机运行于 PRoot 用户态，无需 root。
- 未分组会话独享工作区；归入项目的会话共享工作区、附件与浏览器缓存，日记始终按会话隔离。
- 模型接口 OpenAI 兼容；缺少参数的模型 id 按目录补全（上下文窗口、输出上限、思考档位）。
- 主会话可调度子代理、后台任务与交互式 PTY 终端。

**设备自动化**

- 30 个设备工具：系统设置（Wi-Fi、蓝牙、音量、亮度、勿扰、手电筒、闹钟）、应用启动与深链、通知读取/回复/关闭、通讯录/短信/通话记录/日历、媒体控制、剪贴板、定位。部分能力经 [Shizuku](https://github.com/RikkaApps/Shizuku) 增强；每个工具都有无 Shizuku 时的降级路径。
- 虚拟屏：Agent 在独立虚拟屏上操作应用；遇到登录、验证码、付款页面时移交用户处理，完成后自动收回继续执行。
- 自动化规则：定时之外支持 Wi-Fi/蓝牙连接、充电、电量、亮/灭/解锁屏、收到通知、打开应用等触发器，按规则级工具白名单无人值守执行。
- 防护机制：重复动作、无效动作、连续失败先提醒后叫停；"发送/提交"类对外动作自动保持节奏。

**助手层**

- 结果卡片：面向用户的工具结果（状态、列表、媒体、文件、日程、状态变更、链接）渲染为类型化卡片；状态变更支持一键撤销（以旧值本地重放原工具）。
- App 知识：内置 16 个常用应用的操作手册，首次进入自动注入；Agent 同时维护每应用的本机经验笔记。
- Agent2API 网关：上游 [Agent2API](https://github.com/aimod-cc/Agent2API) 引擎（锁定 `v3.0.8`）以应用托管进程运行，把各家账号聚合为 OpenAI 兼容接口，管理面板完整内置。
- 快捷唤起：悬浮球、快捷磁贴、系统分享、桌面小部件均直达半屏命令面板。
- 内置浏览器（桌面/移动双形态）、MCP 客户端与服务端、全局技能，以及 `/var/minis/memory` 下的会话日记。

## 从源码构建

PRoot 与 Ubuntu rootfs 在构建时生成，不进仓库。需要 NDK r29（`29.0.14206865`）。完整步骤见 [BUILDING.md](BUILDING.md)。

```sh
git clone https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
git submodule update --init deps/proot
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleRelease
```

说明：

- `deps/proot` 是 Android 构建唯一需要的子模块；`deps/ish` 为 iOS 遗留子模块，不参与编译。
- 仅有 release 变体——`debug` 变体已在 `app/build.gradle.kts` 中全部停用。
- 不要用 sdkmanager 的 x86_64 包覆盖客户机内的 aarch64 `aapt2`。网络镜像由内置技能 `android-sdk-mirrors` 提供。

## 仓库结构

```
src/android/    Android 应用（Kotlin / Compose）与 JNI
src/shared/     共享资源
deps/           原生依赖构建脚本
docs/           说明与开发文档
release-notes/  各版本发行说明
scripts/        rootfs 与开发脚本
```

## 第三方组件

权威清单（含版本与许可证）见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。摘要：

**原生（`deps/`）**

| 组件 | 许可证 | 用途 |
|---|---|---|
| [proot](https://github.com/OpenMinis/proot)（fork） | GPL-2.0 | Linux 沙箱（`deps/proot` 子模块） |
| [talloc](https://talloc.samba.org) | LGPL-3.0-or-later | proot 依赖的内存分配器 |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | MIT | 中文分词 |
| Ubuntu 24.04 (noble) arm64 rootfs | Canonical / Ubuntu 软件包许可 | PRoot 客户机，构建时下载 |
| [android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools)（aarch64）与 Android SDK 命令行工具 | Apache-2.0（AOSP） | 客户机内的 aapt2 / zipalign / adb / sdkmanager |

**Android（Gradle）**

| 组件 | 许可证 |
|---|---|
| AndroidX / Jetpack（Compose、Room、Navigation、DataStore、security-crypto、webkit 等） | Apache-2.0 |
| OkHttp + okhttp-sse、kotlinx-coroutines、kotlinx-serialization-json、Coil、mikepenz multiplatform-markdown-renderer、Reorderable、ACRA | Apache-2.0 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) API + provider | MIT |
| Mozilla Rhino | MPL-2.0 |

**随包分发**

| 组件 | 许可证 |
|---|---|
| [Agent2API](https://github.com/aimod-cc/Agent2API) 网关与 web 面板（锁定 `v3.0.8`，CI 构建） | MIT |
| KaTeX、jieba 词典 | MIT |

**改写源码**：部分 Android agent 组件改写自 [XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public)（GPL-3.0-or-later），组件清单见 THIRD_PARTY_LICENSES.md。

仅测试使用的依赖（JUnit 4、MockWebServer、kotlinx-coroutines-test、org.json）同样列于 THIRD_PARTY_LICENSES.md。

## 问题反馈

Bug 与功能请求请提交到 [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues)。
