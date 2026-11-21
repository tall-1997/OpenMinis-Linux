#!/usr/bin/env bash
# 运行期令牌断言：真编译 + 真执行，验证 MinisColors 的取值正确。
#
# 为什么需要 —— 两个静态检查都有盲区：
#   check_ui_tokens.py       证明「生成物与源码一致」，不问值对不对
#   check_token_semantics.py 解析源码文本做结构断言，抓不到「解析对了但算错了」
#
# 这个脚本把 MinisTokens.kt 和 TokensProbe.kt 单独编译再跑一遍，在真实 JVM 上
# 实例化 Light/Dark 并断言对比度、灰阶层次、diff 配色方向。
# 不需要 Android SDK、不需要 gradle、不需要模拟器 —— 这正是令牌分两层的回报。
#
# 编译器查找顺序（任一命中即可）：
#   1. $KOTLINC
#   2. PATH 上的 kotlinc
#   3. Gradle 缓存里的 kotlin-compiler-embeddable-*.jar（CI 走这条，JDK 已有）
# 三者都没有时 SKIP（不阻断 PR），并打印怎么装。
#
# 用法：bash scripts/ui/probe/run_probe.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TOKENS="$ROOT/src/android/app/src/main/java/com/openminis/app/ui/tokens/MinisTokens.kt"
PROBE="$ROOT/scripts/ui/probe/TokensProbe.kt"

for f in "$TOKENS" "$PROBE"; do
  [ -f "$f" ] || { echo "token probe: SKIP ($f not found)"; exit 0; }
done

if ! command -v java >/dev/null 2>&1; then
  echo "token probe: SKIP (java not found)"
  exit 0
fi

WORK="$(mktemp -d -t tokprobe-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
cp "$TOKENS" "$PROBE" "$WORK/"
cd "$WORK"

compile_with() {
  # $1 = 可执行命令；剩余参数为编译器 jar
  "$@" MinisTokens.kt TokensProbe.kt -include-runtime -d probe.jar 2>&1 \
    | grep -v '^Picked up' || true
  [ -f probe.jar ]
}

COMPILED=0

# 1/2. kotlinc 可执行文件
KOTLINC_BIN="${KOTLINC:-}"
if [ -z "$KOTLINC_BIN" ] && command -v kotlinc >/dev/null 2>&1; then
  KOTLINC_BIN="$(command -v kotlinc)"
fi
if [ -n "$KOTLINC_BIN" ] && [ -x "$KOTLINC_BIN" ]; then
  compile_with "$KOTLINC_BIN" && COMPILED=1
fi

# 3. Gradle 缓存里的 embeddable 编译器
if [ "$COMPILED" -eq 0 ]; then
  CACHE_JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -name \
    'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort -V | tail -1 || true)"
  if [ -n "$CACHE_JAR" ]; then
    echo "token probe: using $CACHE_JAR"
    compile_with java -cp "$CACHE_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      && COMPILED=1
  fi
fi

if [ "$COMPILED" -eq 0 ]; then
  echo "token probe: SKIP (no kotlinc: set KOTLINC, add kotlinc to PATH,"
  echo "  or pre-populate the Gradle cache by running any Kotlin build once)"
  exit 0
fi

java -jar probe.jar 2>&1 | grep -v '^Picked up'
