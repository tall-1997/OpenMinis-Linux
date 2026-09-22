# Minis Ultra

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android%20arm64-lightgrey.svg)](#下载)
[![Release](https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases)](https://github.com/tall-1997/OpenMinis-Linux/releases)

Android arm64 上的私人 AI Agent。把 Claude、GPT、Gemini 和兼容中转接到手机里的一台 Ubuntu 24.04：能装包、跑脚本、用浏览器、技能和记忆，也能派出子代理。

包名 `com.openminis.linux`，启动器名称 **Minis Ultra**。可与官方 OpenMinis 并排安装。检查更新只指向本仓库。

## 下载

当前版本 **1.36.23-linux**（versionCode 76）。

- 发行包：[1.36.23-linux](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/1.36.23-linux) → `minis-ultra-com.openminis.linux.apk`
- 滚动构建：[android-latest](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)（`main` 每次成功构建覆盖）

侧载前允许「安装未知应用」。debug 签名不能覆盖另一张证书装上的版本，见 [docs/SIGNING.md](docs/SIGNING.md)。

## 能做什么

- **沙箱**：Ubuntu 24.04 arm64，跑在 PRoot 里。客户机命令、主机 `su`、共享存储见 [LINUX.md](LINUX.md)。
- **会话**：未分组时一会话一工作区；归入项目后共享工作区、附件和浏览器缓存，日记仍按会话隔离。
- **模型**：接兼容接口；缺参数的模型 id 会按目录补全上下文、输出长度和思考档位。
- **多智能体**：主会话调度子代理。设置 → 多智能体。
- **技能与记忆**：全局技能；本会话日记在 `/var/minis/memory`，`GLOBAL.md` 在设置里单独维护。

## 从源码构建

PRoot 和 Ubuntu rootfs 在构建时生成，不进仓库。需要 NDK r28+。完整步骤见 [BUILDING.md](BUILDING.md)。

```sh
git clone --recurse-submodules https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleDebug
```

国内镜像见 [docs/android-sdk-mirrors.md](docs/android-sdk-mirrors.md)。不要用 sdkmanager 的 x86_64 包覆盖客户机里的 aarch64 `aapt2`。

## 目录

```
src/android/    Android 应用（Kotlin / Compose）与 JNI
src/shared/     共享资源
deps/           原生依赖构建脚本
docs/           说明与发行注记
scripts/        rootfs 与开发脚本
```

## 更新日志

本版修了从旧版升级时自定义人格、已关闭技能和已装 Ubuntu 沙箱被清掉，以及长安装被 10 分钟超时掐掉的问题。详细说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

## 许可

[GNU GPL v3.0](LICENSE)。沙箱链接 PRoot（GPLv2），合并作品按 GPLv3 分发。第三方许可见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

问题与功能请求请提到 [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues)。
