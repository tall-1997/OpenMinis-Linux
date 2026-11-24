#!/usr/bin/env bash
# 为钉死的 NDK r29（29.0.14206865）交叉编译 LLVM libunwind 静态库，安装进
#   $NDK/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/
#
# NDK r28 起 sysroot 不再提供 libunwind.so；r29 同样没有可用的 host
# libunwind.so。crash_handler.cpp 仍调用 _Unwind_Backtrace / _Unwind_GetIP，
# 且链接带 -Wl,--no-undefined。只接受修订号 29.0.14206865，不把已有 r28 当成目标。
# 链接期需要静态库 + -lunwind（由 build_apk_aarch64.sh 给包装 clang++ 追加）。
#
# 用法:
#   scripts/build_libunwind_aarch64.sh [ndk_path ...]
# 无参数时使用 ANDROID_NDK_HOME / ANDROID_NDK_ROOT / $ANDROID_HOME/ndk/29.0.14206865
#
# 环境变量:
#   LLVM_SRC      源码目录，默认 /opt/llvm-project（已存在则跳过 clone）
#   LLVM_BRANCH   默认 release/18.x
#   LLVM_URL      默认 https://github.com/llvm/llvm-project.git
#   LIBUNWIND_BUILD  cmake 构建目录，默认 /tmp/openminis-libunwind-aarch64
#   FORCE_LIBUNWIND=1  即使 sysroot 已有符号也重编
set -euo pipefail

log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLVM_SRC="${LLVM_SRC:-/opt/llvm-project}"
LLVM_BRANCH="${LLVM_BRANCH:-release/18.x}"
LLVM_URL="${LLVM_URL:-https://github.com/llvm/llvm-project.git}"
BUILD_DIR="${LIBUNWIND_BUILD:-/tmp/openminis-libunwind-aarch64}"
SPARSE_DIRS=(
  libunwind
  cmake
  llvm/cmake
  llvm/include
  runtimes/cmake
  runtimes/include
  compiler-rt/cmake
)

PINNED_NDK_REV="29.0.14206865"

ndk_pkg_revision() {
  local dir="$1" rev=""
  if [[ -f "$dir/source.properties" ]]; then
    rev=$(awk -F= '/Pkg.Revision/ { gsub(/[ \t]/, "", $2); print $2; exit }' "$dir/source.properties")
  fi
  if [[ -z "$rev" ]]; then
    rev=$(basename "$dir")
  fi
  printf '%s\n' "$rev"
}

require_pinned_ndk() {
  local dir="$1" rev
  [[ -d "$dir" ]] || die "NDK 不存在: $dir"
  rev=$(ndk_pkg_revision "$dir")
  [[ "$rev" == "$PINNED_NDK_REV" ]] || die "需要 NDK $PINNED_NDK_REV，拒绝 $dir（revision ${rev:-unknown}）。不要用 r28。"
}

collect_ndks() {
  local -a out=()
  local p sdk
  if [[ "$#" -gt 0 ]]; then
    for p in "$@"; do
      require_pinned_ndk "$p"
      out+=("$p")
    done
  else
    for p in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
      if [[ -n "$p" && -d "$p" ]]; then
        require_pinned_ndk "$p"
        out+=("$p")
      fi
    done
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -d "$sdk/ndk/$PINNED_NDK_REV" ]]; then
      out+=("$sdk/ndk/$PINNED_NDK_REV")
    fi
  fi
  if [[ "${#out[@]}" -eq 0 ]]; then
    die "未找到 NDK $PINNED_NDK_REV。传入路径，或设置 ANDROID_NDK_HOME / ANDROID_HOME"
  fi
  # 去重
  printf '%s\n' "${out[@]}" | awk 'NF && !seen[$0]++'
}

ndk_prebuilt() {
  local ndk="$1" tag
  for tag in linux-x86_64 linux-aarch64 linux-arm64 darwin-x86_64 windows-x86_64; do
    if [[ -d "$ndk/toolchains/llvm/prebuilt/$tag" ]]; then
      echo "$ndk/toolchains/llvm/prebuilt/$tag"
      return 0
    fi
  done
  return 1
}

sysroot_lib_dirs() {
  local prebuilt="$1"
  local base="$prebuilt/sysroot/usr/lib/aarch64-linux-android"
  [[ -d "$base" ]] || return 1
  echo "$base"
  local d
  for d in "$base"/[0-9]*; do
    [[ -d "$d" ]] && echo "$d"
  done
}

has_unwind_sym() {
  local archive="$1"
  [[ -f "$archive" ]] || return 1
  local nm_bin=""
  if command -v llvm-nm >/dev/null 2>&1; then
    nm_bin="llvm-nm"
  elif command -v nm >/dev/null 2>&1; then
    nm_bin="nm"
  else
    return 1
  fi
  "$nm_bin" "$archive" 2>/dev/null | grep -E ' [TW] _Unwind_Backtrace$' >/dev/null
}

ndk_already_ready() {
  local ndk="$1"
  local prebuilt base
  prebuilt="$(ndk_prebuilt "$ndk")" || return 1
  base="$prebuilt/sysroot/usr/lib/aarch64-linux-android/libunwind.a"
  has_unwind_sym "$base"
}

find_cmake() {
  if command -v cmake >/dev/null 2>&1; then
    command -v cmake
    return 0
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" d
  for d in "${sdk:-}/cmake" /opt/android-sdk/cmake; do
    [[ -d "$d" ]] || continue
    local c
    c="$(ls -1d "$d"/*/bin/cmake 2>/dev/null | tail -n 1 || true)"
    if [[ -n "$c" && -x "$c" ]]; then
      echo "$c"
      return 0
    fi
  done
  die "未找到 cmake"
}

find_ninja() {
  if command -v ninja >/dev/null 2>&1; then
    command -v ninja
    return 0
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" d
  for d in "${sdk:-}/cmake" /opt/android-sdk/cmake; do
    [[ -d "$d" ]] || continue
    local n
    n="$(ls -1d "$d"/*/bin/ninja 2>/dev/null | tail -n 1 || true)"
    if [[ -n "$n" && -x "$n" ]]; then
      echo "$n"
      return 0
    fi
  done
  echo ""
}

ensure_llvm_src() {
  if [[ -f "$LLVM_SRC/libunwind/CMakeLists.txt" ]]; then
    log "复用已有源码 $LLVM_SRC"
    return 0
  fi
  command -v git >/dev/null 2>&1 || die "需要 git 才能拉取 llvm-project"
  if ! mkdir -p "$(dirname "$LLVM_SRC")" 2>/dev/null; then
    LLVM_SRC="$ROOT/.cache/llvm-project"
    log "/opt 不可写，改用 $LLVM_SRC"
    if [[ -f "$LLVM_SRC/libunwind/CMakeLists.txt" ]]; then
      log "复用已有源码 $LLVM_SRC"
      return 0
    fi
    mkdir -p "$(dirname "$LLVM_SRC")"
  fi
  if [[ -d "$LLVM_SRC/.git" ]]; then
    log "补全 sparse-checkout: $LLVM_SRC"
    git -C "$LLVM_SRC" sparse-checkout set "${SPARSE_DIRS[@]}"
    [[ -f "$LLVM_SRC/libunwind/CMakeLists.txt" ]] || die "sparse-checkout 后仍无 libunwind"
    return 0
  fi
  if [[ -e "$LLVM_SRC" && ! -d "$LLVM_SRC/.git" ]]; then
    die "$LLVM_SRC 已存在但不是 git 仓库，且缺少 libunwind/CMakeLists.txt"
  fi
  log "sparse clone $LLVM_URL ($LLVM_BRANCH) -> $LLVM_SRC"
  git clone --depth 1 --filter=blob:none --sparse --branch "$LLVM_BRANCH" \
    "$LLVM_URL" "$LLVM_SRC"
  git -C "$LLVM_SRC" sparse-checkout set "${SPARSE_DIRS[@]}"
  [[ -f "$LLVM_SRC/libunwind/CMakeLists.txt" ]] || die "clone 后仍无 libunwind"
}

build_static_lib() {
  local ndk="$1"
  local toolchain="$ndk/build/cmake/android.toolchain.cmake"
  [[ -f "$toolchain" ]] || die "缺少 android.toolchain.cmake: $toolchain"
  local cmake_bin ninja_bin prebuilt host_tag
  cmake_bin="$(find_cmake)"
  ninja_bin="$(find_ninja)"
  prebuilt="$(ndk_prebuilt "$ndk")" || die "NDK 无 llvm prebuilt: $ndk"
  host_tag="$(basename "$prebuilt")"

  rm -rf "$BUILD_DIR"
  mkdir -p "$BUILD_DIR"

  local -a cmake_args=(
    -S "$LLVM_SRC/libunwind"
    -B "$BUILD_DIR"
    -DCMAKE_TOOLCHAIN_FILE="$toolchain"
    -DANDROID_ABI=arm64-v8a
    -DANDROID_PLATFORM=android-26
    -DANDROID_STL=none
    -DANDROID_NDK="$ndk"
    -DANDROID_HOST_TAG="$host_tag"
    -DANDROID_NDK_HOST_SYSTEM_NAME="$host_tag"
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
    -DLIBUNWIND_ENABLE_SHARED=OFF
    -DLIBUNWIND_ENABLE_STATIC=ON
    -DLIBUNWIND_ENABLE_THREADS=ON
    -DLIBUNWIND_HAS_PTHREAD_LIB=OFF
    -DCMAKE_THREAD_LIBS_INIT=
    -DCMAKE_HAVE_LIBC_PTHREAD=1
    -DLIBUNWIND_ENABLE_CROSS_UNWINDING=ON
    -DLIBUNWIND_USE_COMPILER_RT=ON
    -DLIBUNWIND_INCLUDE_TESTS=OFF
    -DLIBUNWIND_ENABLE_ASSERTIONS=OFF
    -DLIBUNWIND_INSTALL_HEADERS=OFF
    -DLLVM_INCLUDE_TESTS=OFF
    -DLLVM_ENABLE_PER_TARGET_RUNTIME_DIR=OFF
    -DCMAKE_INSTALL_PREFIX="$BUILD_DIR/install"
  )
  if [[ -n "$ninja_bin" ]]; then
    cmake_args+=(-G Ninja -DCMAKE_MAKE_PROGRAM="$ninja_bin")
  fi

  log "cmake 配置 libunwind (NDK=$ndk)"
  "$cmake_bin" "${cmake_args[@]}"

  log "编译 libunwind.a"
  if [[ -n "$ninja_bin" ]]; then
    "$cmake_bin" --build "$BUILD_DIR" --target unwind --parallel \
      || "$cmake_bin" --build "$BUILD_DIR" --parallel
  else
    "$cmake_bin" --build "$BUILD_DIR" --target unwind --parallel "$(nproc 2>/dev/null || echo 2)" \
      || "$cmake_bin" --build "$BUILD_DIR" --parallel "$(nproc 2>/dev/null || echo 2)"
  fi

  local archive=""
  archive="$(find "$BUILD_DIR" -name libunwind.a -type f -print -quit 2>/dev/null || true)"
  [[ -n "$archive" && -f "$archive" ]] || die "构建完成但未找到 libunwind.a"
  has_unwind_sym "$archive" || die "$archive 中没有 _Unwind_Backtrace"
  ARCHIVE="$archive"
}

install_archive() {
  local archive="$1"
  local ndk="$2"
  local prebuilt dest dir
  prebuilt="$(ndk_prebuilt "$ndk")" || die "NDK 无 llvm prebuilt: $ndk"
  while IFS= read -r dir; do
    [[ -n "$dir" ]] || continue
    mkdir -p "$dir"
    dest="$dir/libunwind.a"
    cp -f "$archive" "$dest"
    log "安装 $dest"
  done < <(sysroot_lib_dirs "$prebuilt")
  has_unwind_sym "$prebuilt/sysroot/usr/lib/aarch64-linux-android/libunwind.a" \
    || die "安装后 sysroot 仍无 _Unwind_Backtrace"
}

# --- main ---
mapfile -t NDKS < <(collect_ndks "$@")
log "目标 NDK: ${NDKS[*]}"

need_build=0
if [[ "${FORCE_LIBUNWIND:-}" == "1" ]]; then
  need_build=1
else
  for ndk in "${NDKS[@]}"; do
    if ndk_already_ready "$ndk"; then
      log "已就绪，跳过编译: $ndk"
    else
      need_build=1
    fi
  done
fi

ARCHIVE=""
if [[ "$need_build" -eq 1 ]]; then
  ensure_llvm_src
  build_static_lib "${NDKS[0]}"
  log "产物 $ARCHIVE"
fi

for ndk in "${NDKS[@]}"; do
  if [[ "$need_build" -eq 0 ]] && ndk_already_ready "$ndk"; then
    continue
  fi
  if [[ -z "$ARCHIVE" ]]; then
    ensure_llvm_src
    build_static_lib "$ndk"
  fi
  install_archive "$ARCHIVE" "$ndk"
done

log "libunwind.a 已安装到全部 NDK sysroot"
exit 0
