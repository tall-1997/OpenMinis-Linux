# Minis Ultra 1.36.47-linux

versionCode **100**。包名 `com.openminis.linux`。产品名仍是 Minis Ultra。

## 本版

- explore / plan 可以跑 `date`、`uname`、`cat`、`ls`、`df`。写入和重定向仍拒绝。
- `2>文件`、`1>>文件`、`&>文件`、`>|文件` 不再被当成普通检查命令放行。以前只要 `>` 前面有数字就会漏掉。
- `2>&1`、`>/dev/null`、`2>/dev/null` 仍可用。引号里的 `>`、here-doc 正文里的 `>` 不再误伤。
- `sh -c`、`bash -lc`、`env bash -c`、`busybox sh -c` 和 `$(...)` 里面的写入、重定向同样拒绝。`sed -i`、`perl -i` 也算写入。
- `write_paths=none` 走测试用的 swap 入口时不再把 `none` 丢掉。正式协程路径本来就会拒绝写入。
