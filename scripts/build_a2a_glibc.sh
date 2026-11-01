#!/usr/bin/env bash
# Builds the a2a plugin glibc runtime for Android.
#
# The gateway's provider plugins ship as upstream-built glibc binaries
# (`aarch64-unknown-linux-gnu`). Android has no glibc loader and no /etc,
# so the plugins need a small self-contained glibc runtime. This script
# cross-compiles glibc with crosstool-ng and retargets its compile-time
# network paths (resolv.conf / hosts / nsswitch.conf) into the app's
# private data dir — the only place a no-root app process can write.
# A plain prebuilt glibc cannot work here: binary-patching the /etc
# strings requires equal-length replacement paths, and an app has no
# 16-byte writable path (the /data/a2a trick is root-only).
#
# Usage: scripts/build_a2a_glibc.sh [output.tar.gz]
#   Default output: native-artifacts/assets/a2a-glibc.tar.gz
#
# Idempotent: when a previous crosstool-ng prefix exists under
# /tmp/a2a-ctng (e.g. restored from the actions cache), the toolchain
# build is skipped and only extraction + packaging run.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/native-artifacts/assets/a2a-glibc.tar.gz}"
WORK=/tmp/a2a-glibc-build
PREFIX=/tmp/a2a-ctng
PATCH_DIR="$ROOT/scripts/a2a-glibc"
APP_DATA="/data/user/0/com.openminis.linux/files/builtin-plugins/a2a/data"

# GitHub runners drop privileges to the `runner` user; run root-only
# steps (apt, writing crosstool-ng's /usr/lib package dir) through sudo
# when needed. sudo passes -E for DEBIAN_FRONTEND via the env prefix below,
# not the surrounding environment.
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    SUDO="sudo"
fi

# --- toolchain deps (crosstool-ng builds gcc+glibc+binutils from source) ---
# There is no crosstool-ng package in Ubuntu jammy (any pocket), so it is
# bootstrapped from the pinned release tarball below. The host packages are
# its build/runtime deps (bootstrap needs autoconf/automake/libtool).
CTNG_VERSION=1.29.0
CTNG_TARBALL="crosstool-ng-$CTNG_VERSION.tar.xz"
CTNG_SRC=/tmp/a2a-ctng-src

$SUDO apt-get update -qq
$SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    autoconf automake libtool libtool-bin \
    bison flex texinfo gawk gperf help2man \
    libncurses-dev rsync unzip bzip2 xz-utils file >/dev/null

if ! command -v ct-ng >/dev/null 2>&1; then
    rm -rf "$CTNG_SRC"
    mkdir -p "$CTNG_SRC"
    curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
        -o "$CTNG_SRC/$CTNG_TARBALL" \
        "https://github.com/crosstool-ng/crosstool-ng/releases/download/crosstool-ng-$CTNG_VERSION/$CTNG_TARBALL"
    tar xf "$CTNG_SRC/$CTNG_TARBALL" -C "$CTNG_SRC"
    cd "$CTNG_SRC/crosstool-ng-$CTNG_VERSION"
    ./bootstrap
    ./configure --prefix=/usr/local
    make -j"$(nproc)"
    $SUDO make install
fi

GLIBC_PKG_DIR="/usr/local/share/crosstool-ng/packages/glibc"
# The patch targets one specific glibc release; derive that version from
# the patch file name instead of picking the newest directory, so the
# patch always lands where it applies.
GLIBC_VER="$(find "$PATCH_DIR" -maxdepth 1 -name 'glibc-*.patch' 2>/dev/null | sed 's/.*glibc-\([0-9.]*\).*/\1/' | sort -V | tail -1)"
if [ -z "$GLIBC_VER" ] || [ ! -d "$GLIBC_PKG_DIR/$GLIBC_VER" ]; then
    echo "glibc $GLIBC_VER not supported by crosstool-ng $CTNG_VERSION" >&2
    exit 1
fi
echo "crosstool-ng $CTNG_VERSION, glibc $GLIBC_VER"

# --- stage the path patch into the crosstool-ng package dir -------------
# crosstool-ng applies packages/<pkg>/<version>/*.patch automatically.
$SUDO cp "$PATCH_DIR"/glibc-*.patch "$GLIBC_PKG_DIR/$GLIBC_VER/"

# --- build (skipped when the prefix was restored from cache) ------------
if [ ! -d "$PREFIX" ] || [ ! -f "$(find "$PREFIX" -name ld-linux-aarch64.so.1 2>/dev/null | head -1)" ]; then
    rm -rf "$WORK" "$PREFIX"
    mkdir -p "$WORK"
    cd "$WORK"
    ct-ng aarch64-unknown-linux-gnu >/dev/null
    # Pin the glibc release to the version the path patch targets: turn
    # off every release option, then switch exactly ours back on. The
    # sample default would silently drift to whatever ct-ng ships newest.
    GLIBC_CFG="CT_GLIBC_V_${GLIBC_VER//./_}"
    sed -i -E "s/^CT_GLIBC_V_2_[0-9_]+=y/# \\0/" .config
    sed -i -E "s/^# ${GLIBC_CFG} is not set/${GLIBC_CFG}=y/" .config
    grep -q "^${GLIBC_CFG}=y$" .config \
        || { echo "failed to pin glibc $GLIBC_VER in .config" >&2; exit 1; }
    # ct-ng 1.29 selects glibc by the version string as well: the V_*
    # booleans are kconfig legacy and do_libc_get consumes
    # CT_GLIBC_VERSION, so the booleans alone leave the sample default
    # (newest) in charge of the actual download.
    sed -i -E "s|^#? ?CT_GLIBC_VERSION=.*|CT_GLIBC_VERSION=\"$GLIBC_VER\"|" .config
    grep -q "^CT_GLIBC_VERSION=\"$GLIBC_VER\"$" .config \
        || { echo "failed to pin glibc $GLIBC_VER version string" >&2; exit 1; }
    # Pin gcc the same way: the 1.29.0 sample defaults to the newest
    # gcc (16.x), which multiplies build time on 2-core runners and is
    # unproven on the 22.04 host toolchain. 12.5 keeps the runtime in
    # the same libstdc++ generation as the previous ct-ng 1.24 chain.
    # (gcc is the one package 1.29 selects via the version string, not
    # a CT_GCC_V_* boolean — all of those sit commented out.)
    GCC_VER=12.5.0
    sed -i -E "s|^#? ?CT_GCC_VERSION=.*|CT_GCC_VERSION=\"$GCC_VER\"|" .config
    grep -q "^CT_GCC_VERSION=\"$GCC_VER\"$" .config \
        || { echo "failed to pin gcc $GCC_VER in .config" >&2; exit 1; }
    # Trim what a plugin runtime never needs; keep the sysroot full.
    sed -i \
        -e 's|^CT_PREFIX_DIR=.*|CT_PREFIX_DIR="/tmp/a2a-ctng"|' \
        -e 's|^CT_GDB_CROSS=y|CT_GDB_CROSS=n|' \
        -e 's|^CT_DEBUG_GDB=y|CT_DEBUG_GDB=n|' \
        -e 's|^CT_STRIP_HOST_TOOLCHAIN_EXECUTABLES=y|CT_STRIP_HOST_TOOLCHAIN_EXECUTABLES=n|' \
        .config
    ct-ng build
else
    echo "crosstool-ng prefix present — skipping toolchain build"
fi

# --- extract the runtime pieces -----------------------------------------
LOADER="$(find "$PREFIX" -name ld-linux-aarch64.so.1 | head -1)"
[ -n "$LOADER" ] || { echo "no glibc loader built" >&2; exit 1; }
SYSROOT_LIB="$(dirname "$LOADER")"
SYSROOT_ROOT="$(dirname "$SYSROOT_LIB")"
GCC_LIB="$(find "$PREFIX" -name libgcc_s.so.1 -path "*aarch64-unknown-linux-gnu*" | head -1)"

rm -rf "$WORK/out"
mkdir -p "$WORK/out/a2a-glibc"
for lib in \
    ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libresolv.so.2 \
    libnss_dns.so.2 libnss_files.so.2 libutil.so.1 libpthread.so.0 \
    libdl.so.2 librt.so.1; do
    # -L (dereference): the installed sonames are symlinks
    # (libc.so.6 -> libc-2.39.so). Packing the symlink without its target
    # ships a dangling loader/libc and every plugin exec fails with ENOENT;
    # the deployer wants real files, not links.
    if [ -f "$SYSROOT_LIB/$lib" ] || [ -L "$SYSROOT_LIB/$lib" ]; then
        cp -L "$SYSROOT_LIB/$lib" "$WORK/out/a2a-glibc/"
    fi
done
if [ -n "$GCC_LIB" ]; then cp "$GCC_LIB" "$WORK/out/a2a-glibc/"; fi
if [ -d "$SYSROOT_ROOT/usr/share/zoneinfo" ]; then
    cp -r "$SYSROOT_ROOT/usr/share/zoneinfo" "$WORK/out/a2a-glibc/zoneinfo"
fi

# --- verify: the retarget must be IN the built binary --------------------
# A silent miss here ships a runtime that still reads /etc and every
# plugin loses DNS on-device — fail loudly instead.
if ! strings "$WORK/out/a2a-glibc/libc.so.6" | grep -qF "$APP_DATA/resolv.conf"; then
    echo "built libc does not contain the retargeted resolv.conf path" >&2
    exit 1
fi
if ! file "$WORK/out/a2a-glibc/ld-linux-aarch64.so.1" | grep -q "ARM aarch64"; then
    echo "loader is not an ARM aarch64 binary" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"
tar czf "$OUT" -C "$WORK/out" a2a-glibc
echo "OK: $OUT ($(du -h "$OUT" | cut -f1))"
tar tzf "$OUT" | head -12
