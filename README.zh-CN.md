<p align="right"><a href="README.md">English</a> · <b>中文</b></p>

# Minis Ultra

<p>
  <a href="https://github.com/tall-1997/OpenMinis-Linux/releases"><img alt="Release" src="https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases"></a>
  <a href="LICENSE"><img alt="License: GPL v3" src="https://img.shields.io/badge/License-GPLv3-blue.svg"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Android-arm64-3ddc84">
  <img alt="Guest" src="https://img.shields.io/badge/Guest-Ubuntu%2024.04-E95420">
</p>

**一部手机，一个能动手的 AI。**

Minis Ultra 是运行在 Android arm64 上的本地私人 AI 助手。它不只是"聊天"——手机里有一个完整的 Ubuntu 24.04 Linux 沙箱和一层设备自动化能力，模型可以在里面装软件、写代码、跑脚本、操作 app、改系统设置，全部在你自己的手机上完成。

> 无需 root。对话不上传到任何第三方服务器——除了你自己配置的模型端点。

---

## 三个执行环境，一个会话

模型在同一个会话里可以自由切换三种"动手"方式：

```
┌─────────────────────────────────────────────────┐
│                   Minis Ultra 会话               │
├────────────────┬────────────────┬───────────────┤
│  Linux 沙箱     │  设备自动化     │  内嵌浏览器     │
│  Ubuntu 24.04  │  30 个结构化    │  桌面/移动双   │
│  PRoot 用户态   │  工具直控手机   │  档 UA，独立   │
│  apt/shell/    │  （可选 Shizuku │  会话浏览器    │
│  文件系统       │  增强）         │  缓存          │
└────────────────┴────────────────┴───────────────┘
```

- **Linux 沙箱** — 用户态 PRoot 跑起的 Ubuntu 24.04 arm64 客户系统：独立包管理器、shell、文件系统。有项目的会话共享工作区，没有项目的会话各得一个专属工作区；会话日记永远私有（详见 [LINUX.md](LINUX.md)）。
- **设备自动化** — 30 个结构化工具：Wi-Fi/蓝牙/音量/亮度/勿扰/手电筒/闹钟等系统设置、app 启动与深链、通知读取/回复/免打扰、联系人/短信/通话记录/日历、媒体控制、剪贴板、定位。装了 [Shizuku](https://github.com/RikkaApps/Shizuku) 的能力自动增强，没装每个工具都有文档化回退。
- **专属虚拟屏** — agent 在自己的虚拟屏上操作 app，验证码、登录、支付页面会自动把屏幕交还给你，完成后再接回去。

## 它能干什么

几个真实场景：

- 🛠️ "帮我装 Node 然后把这个仓库跑起来" → 沙箱内 apt + git + npm 一条龙
- 📱 "把微信里的验证码读出来填到浏览器里" → 通知读取 + 虚拟屏自动操作
- ⏰ "每天早上 8 点把昨天的日程整理成清单发给我" → 定时自动化规则，无人值守运行
- 🔌 "把我本地的数据库工具接进来" → MCP 客户端外接任意工具服务器

## 多代理与自动化

- **主会话派发子代理**、后台 job 与交互式 PTY 终端并行工作
- **自动化规则**：定时计划 + Wi-Fi/蓝牙连接、充电、电量、屏幕状态、通知、应用前台等触发器，在逐规则工具白名单下无人值守执行
- **护栏机制**：重复无效动作、连续失败自动警告并停止；对外动作（发送、提交）自动节流
- **结果卡片**：工具结果渲染为可读卡片，状态变更带一键撤销（自动用原值重放原工具）
- **App 经验库**：内置 16 个常用应用的操作指南，agent 会为每个 app 维护自己的使用笔记

## 接入与本地服务

- 任意 **OpenAI 兼容端点**；未公布限额的模型从模型目录自动补全上下文窗口/输出上限/推理档位
- **MCP 客户端 + 服务端**、全局技能系统、按会话的记忆日记（`/var/minis/memory`）
- **随手唤起**：悬浮球、快捷设置磁贴、系统分享、桌面小部件，四种方式打开半屏命令面板

## 下载

[Releases](https://github.com/tall-1997/OpenMinis-Linux/releases) 提供 `minis-ultra-com.openminis.linux.apk`：

- **版本标签**（如 `2.0.90-linux`）——每个版本的正式构建
- **[android-latest](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)** ——每次 main 构建成功后替换的滚动包

侧载前允许"安装未知应用"。不同签名的构建不能互相覆盖安装，签名说明见 [BUILDING.md](BUILDING.md)。

包名 `com.openminis.linux`，桌面名称 **Minis Ultra**，可与上游 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 并存安装；更新检查只指向本仓库。

## 从源码构建

PRoot 与 Ubuntu rootfs 在构建期生成、不入 git。需要 NDK r29（`29.0.14206865`）。完整步骤见 [BUILDING.md](BUILDING.md)。

```sh
git clone https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
git submodule update --init deps/proot
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleRelease
```

提示：

- `deps/proot` 是 Android 构建唯一需要的子模块；`deps/ish` 为遗留 iOS 子模块，不参与编译
- 仅存在 release 变体（`app/build.gradle.kts` 已禁用 debug）
- 不要用 sdkmanager 的 x86_64 `aapt2` 替换 guest 内的 aarch64 版本；网络镜像由内置 `android-sdk-mirrors` 技能提供

## 仓库结构

```
src/android/    Android 应用（Kotlin / Compose）与 JNI
src/shared/     共享资源
deps/           原生依赖构建脚本
docs/           开发笔记与文档
release-notes/  各版本发布说明
scripts/        rootfs 与开发脚本
```

## 贡献者

本项目以 GPL-3.0 发布，著作权署名是许可证义务；而 squash 合并会把作者写成合并者，贡献者的名字因此容易消失。完整清单见 [CONTRIBUTORS.md](CONTRIBUTORS.md)：

- **tall-1997** —— 维护者：Linux/Android 移植、Agent Loop、工具层、上下文管线、CI 门禁
- **Ethan** —— 仓库基础设施、1.13 Android 线、provider/thinking/storage
- **doubao01** —— 设计系统统一：令牌层、类型化 prefs、11 项 CI 门禁（[#410](https://github.com/tall-1997/OpenMinis-Linux/pull/410)）

漏了你的名字？提个 PR 加一行即可，提交历史就是凭证。

## 许可证

本项目以 [GPL-3.0](LICENSE) 发布。仓库包含并修改 GPL-3 程序，衍生作品须以 GPL-3 相应开源。沙箱链接 [PRoot](https://github.com/proot-me/PRoot/)（GPL-2.0），合并作品按 GPL-3 分发。

组件与版本的**权威清单**见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。摘要：

**原生（`deps/`）**

| 组件 | 许可证 | 用途 |
|---|---|---|
| [proot](https://github.com/OpenMinis/proot)（fork） | GPL-2.0 | Linux 沙箱 |
| [talloc](https://talloc.samba.org) | LGPL-3.0-or-later | proot 所需分配器 |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | MIT | 中文分词 |
| Ubuntu 24.04 arm64 rootfs | Canonical / Ubuntu 包许可 | 构建期下载 |
| [android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools)（aarch64）与 Android SDK 命令行工具 | Apache-2.0（AOSP） | guest 内 aapt2 / zipalign / adb / sdkmanager |

**Android（Gradle）**：AndroidX/Jetpack、OkHttp、kotlinx-coroutines、kotlinx-serialization、Coil、markdown-renderer、Reorderable、ACRA（Apache-2.0）；[Shizuku](https://github.com/RikkaApps/Shizuku)（MIT）；Mozilla Rhino（MPL-2.0）

**随包内置**：KaTeX、jieba 词典（MIT）

**改编来源**：部分 Android agent 组件改编自 [XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public)（GPL-3.0-or-later），组件清单见 THIRD_PARTY_LICENSES.md

## 反馈

Bug 与功能建议请到 [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues)。
