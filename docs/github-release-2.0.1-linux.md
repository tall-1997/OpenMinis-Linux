# Minis Ultra 2.0.1

versionCode **201**，包名 `com.openminis.linux`。

## 稳定性修复

- 建立聊天状态的不可变快照边界，消息列表、附件列表、工具块和流式状态不会再把调用方可变集合直接暴露给 Compose 或后台线程。
- 覆盖流式响应的正常结束、取消、重试、单条 flush、全量 flush 和错误清理路径，避免旧列表视图被后续原地修改。
- 增加回归测试，验证 `ArrayList.subList`、嵌套工具块和流式 delta 在发布后不会被工作列表污染。

## 兼容性

- 包名、会话数据库、Provider HTTP 协议、JNI、沙箱和备份格式保持兼容。
- 可在已安装 2.0.0（versionCode 200）的同签名设备上直接覆盖升级。

## 验证

- Android JVM 全量单元测试通过。
- 快照边界和流式控制器回归测试通过。
- 云端 release workflow 负责生成 arm64-v8a APK。
