# 1.36.22-linux

versionCode **75**。安装包 `minis-ultra-com.openminis.linux.apk`。包名 `com.openminis.linux`，可与官方 OpenMinis 并排安装。

相对 1.36.21-linux。这一版修的是沙箱运行时体检出来、并且在当前代码里仍成立的问题：命令输出被截断、证书被系统命令清掉、apt 关掉证书校验、共享存储没有真正挂进会话 shell、前台服务启动失败会把进程打崩。

## 持久 shell

- 中文等多字节字符不再因为一次 `read` 切在字符中间而变成乱码。未完成的 UTF-8 序列会留到下一块再解码。
- 命令结束标记如果被拆到两次读取里，不再被当成普通输出漏掉，下一条命令也不会一直等。
- 超时或取消会杀掉当前这条命令的进程，而不是只清掉回调、让下一条命令堵在后面。
- 持久 shell 带上 `--kill-on-exit`。宿主进程退出时，客户机里的 shell 一并结束。

## 机内 SDK 与自编译

- 下载 CMake 时不再把安装目录变量盖成压缩包路径，解压后的目录能落到 `ANDROID_HOME/cmake`。
- NDK 默认地址改为已发布的 `android-ndk` 标签和 `android-ndk-r29-aarch64.tar.xz`。旧的 `r29` 标签加 `.zip` 会 404。
- 压缩包支持 `.tar.xz`、`.tar.gz`、`.zip`。缺 `xz` 时再装 `xz-utils`。
- 设置里的实验性自编译改到进程级任务。离开设置页不会取消；只有用户停止或进程结束才会停。
- 找不到源码树时脚本仍以非零退出，不会报空成功。

## 客户机证书

- 主机多出来的 CA 写到 `/usr/local/share/ca-certificates/minis-android/`，同时复制到 `/usr/share/ca-certificates/minis-android/`，并在 `ca-certificates.conf` 里启用。
- `update-ca-certificates` 或重装 `ca-certificates` 重建证书包时，这些主机证书还在。
- AndroidCAStore 按证书指纹去重后再导出。和 Mozilla 包重复的证书不会再写一份。
- `update-ca-certificates` 跑完后，`minis-ca-dedup` 会再扫一遍 bundle，去掉仍然重复的块。钩子设为可执行，`run-parts` 才会调用它。
- 扫描 Mozilla 目录时会跳过我们自己写的 `minis-android`，避免下一轮注入把主机证书当成系统证书删掉。

## 软件源与 TLS

- `minis-dev-setup`、`minis-mirror` 和主机侧重试安装都不再使用 `Acquire::https::Verify-Peer=false`。
- `apt-get update` 失败时先放开 apt 锁，再跑 `minis-mirror auto`（HTTPS 失败会改 HTTP 镜像），然后重试一次。
- `minis-mirror --help` 的镜像列表补上 sjtu。

## 共享存储

- 授予「所有文件访问」后，会话 shell 和交互终端会绑定 `/sdcard`、`/storage/emulated/0`，以及 `/var/minis/mounts/sdcard`。
- 以前只写进了内核侧的挂载表，`shell_execute` 用的是另一份参数，所以客户机里的 `/sdcard` 仍是空目录。
- 权限授予或收回后，下一条命令会重建 shell。用户自己挂的、名字也叫 `sdcard` 的目录不会被覆盖。

## 前台服务与配置

- 应用在后台时，`startForegroundService` 被系统拒绝不会再把调用方打崩。通知是尽力而为，正在跑的任务继续。
- 服务里 `startForeground` 失败会停掉这次服务，避免系统因为没按时调用 `startForeground` 再杀一次进程。
- `minis-config` 可以读到 `security.permissionMode`（只读）。改模式仍在设置 → 权限。

## 安装

侧载前允许「安装未知应用」。debug 签名不能覆盖用另一张证书装上的版本，见 [docs/SIGNING.md](docs/SIGNING.md)。完整说明见 [docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。
