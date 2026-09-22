# 1.36.24-linux

versionCode **77**。安装包 `minis-ultra-com.openminis.linux.apk`。包名 `com.openminis.linux`，可与官方 OpenMinis 并排安装。

相对 1.36.23-linux。这一版修的是手机改时区后，客户机时间仍停在启动时的偏移。

## 时区

- 系统时区变化时，以前只改新 shell 的 `TZ`，并给正在运行的 shell 执行 `export TZ=...`。
- `/etc/localtime` 和 `/etc/timezone` 仍是开机时的时区。不看 `TZ` 的 `date` 和 Python 会显示旧偏移。
- 现在同一次广播会把客户机链接改到手机当前时区。链接保持相对路径，PRoot 才能在客户机里跟着走。
- 时区数据不在 rootfs 里时保持 UTC，不写坏链接。
- PRoot 还没启动时这次广播不做任何事。下次启动会按当时的手机时区写链接。

## 说明

- `LINUX.md` 不再声称本仓库有 iOS 工程。Ubuntu 客户机只用于 Android。

## 安装

侧载前允许「安装未知应用」。debug 签名不能覆盖用另一张证书装上的版本，见 [docs/SIGNING.md](docs/SIGNING.md)。完整说明见 [docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。
