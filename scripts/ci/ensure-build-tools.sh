#!/usr/bin/env bash
# CI 构建工具确保脚本：优先用 runner 镜像预装的工具，缺失才走 apt 兜底。
#
# [T-rolling-apk-blocked] 2026-11-11 run #861 (28de36ab) attempt1/2 双双死在
# native-deps 第一步 "Install build tools and Android NDK"：GitHub 托管
# ubuntu-22.04 runner 的 DNS 解析 apt 镜像域名（azure.archive.ubuntu.com，
# 经 /etc/apt/apt-mirrors.txt 映射）持续失败约 26 分钟，apt-get
# update/install 重试后 exit 100。PRoot/rclone/libunwind/APK 全部被 needs
# 链挡死。同一时段 checkout（github.com）、actions/cache、dl.google.com 均正常
# ——故障域特定于 Ubuntu apt 镜像。
#
# ubuntu-22.04 runner 镜像本就预装下面这些工具（见 runner-images 预装清单），
# 所以正常路径在这里是零 apt 依赖，对该类 DNS 故障免疫；只有未来镜像精简了
# 某个工具时才落进 apt 兜底，兜底自带 update 重试 + Acquire::Retries。
#
# 用法：bash scripts/ci/ensure-build-tools.sh curl unzip make ...
#
# 注意：参数是「命令名」不是包名。ninja-build 是 apt 包名，其命令叫 ninja
# ——run #862 的初版脚本误用 ninja-build 当命令名，预装命中失败落进 apt
# 兜底，正撞上 runner DNS 故障窗口，exit 100。pkg_for_cmd 只在兜底安装时
# 把命令名映射回包名。
set -euo pipefail

pkg_for_cmd() {
  case "$1" in
    ninja) echo ninja-build ;;
    *) echo "$1" ;;
  esac
}

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <command> [<command> ...]" >&2
  exit 2
fi

missing=()
for c in "$@"; do
  if command -v "$c" >/dev/null 2>&1; then
    echo "present: $c ($(command -v "$c"))"
  else
    missing+=("$c")
  fi
done

if [ "${#missing[@]}" -eq 0 ]; then
  echo "all required build tools preinstalled; apt not touched"
  exit 0
fi

echo "missing: ${missing[*]} — falling back to apt (with retries)"
update_ok=0
for i in 1 2 3; do
  if sudo apt-get update; then
    update_ok=1
    break
  fi
  echo "apt-get update attempt ${i}/3 failed; retrying in 30s" >&2
  sleep 30
done
if [ "$update_ok" != 1 ]; then
  echo "::error::apt-get update failed 3 times (runner apt mirror outage?); cannot install: ${missing[*]}"
  exit 1
fi
sudo DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=8 install -y \
  $(for c in "${missing[@]}"; do pkg_for_cmd "$c"; done)
