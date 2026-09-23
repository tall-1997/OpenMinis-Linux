# 1.36.28-linux

versionCode **81**。安装包 `minis-ultra-com.openminis.linux.apk`。包名 `com.openminis.linux`。

相对 1.36.27-linux。对照 2026-09-23 环境检出报告修了确认存在的问题。

## 沙箱和设备信息

- `minis-sessions-cli` 不再被当成会话目录读取。直接读 `minis-sessions/<别人的会话>` 仍然拒绝。
- `android-device all` 不再整棵扫描访客 rootfs，避免 20 秒超时。
- `total_memory_mb` 改为整机内存，不再报虚拟机堆。旧的堆上限改到 `jvm_heap_mb`。
- 工具结果里去掉 `proot info: native_offload` 噪声。日志里的失败码会补回工具退出码。
- 访客 shell 里 `android-device` 等卸载命令若返回 `handler_timeout`，退出码改为 124。直接由 Python 调用同名二进制仍可能是 0，那一段在预编译的 proot 里。
- 时区环境变量改用 `Asia/Shanghai` 这类时区名，不再固定写 `LCL-8`。

## 工具和子代理

- 系统提示改为 GNU bash，并说明 `file_read` 看不到 `/proc`、`/sys` 时要用 shell。
- explore / plan 可以跑只读 shell（`date`、`uname`、`cat`）。写入和重定向仍拒绝。
- 并行 worker 可以写 `write_paths=none`。缺字段时的报错会点名这个字段。

子代理报告偶发中段缺失，代码里的截断会留下 `…(truncated)` 并保留结尾。报告里的现象没有这个标记，更像模型自己没写完，这次没有改截断逻辑。
