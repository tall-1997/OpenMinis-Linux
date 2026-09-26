# Minis Ultra 2.0.3

versionCode **203**，包名 `com.openminis.linux`。

## 压缩

- 仍用当前会话模型压第一次。失败后试「设置 → 模型组 → 默认设置 → 压缩备用模型」一次；未设置备用则用当前模型再试一次。
- 每档只打一枪，单档最多 120 秒。两档都失败则截断更早上文，最近轮次留下。
- 进度条和灰色系统条写明当前是 1/2 还是 2/2、用的哪档模型、失败后是换备用还是截断。
- `no response` / TTFB 允许降体积重试；502/429 仍不拆。

## 崩溃

- 前台服务在 `onCreate` / `onStartCommand` 先挂缓存通知，再构建完整状态栏。STOP、审批、安全模式提前返回前也先 `startForeground`，避免 `ForegroundServiceDidNotStartInTimeException`。

## 兼容性

- 包名、会话数据库、Provider HTTP 协议、JNI、沙箱和备份格式保持兼容。
- 可在已安装 2.0.2（versionCode 202）的同签名设备上直接覆盖升级。

## 验证

- 压缩两档策略与首包超时切分单测已更新。
- 云端 release workflow 负责生成 arm64-v8a APK。
