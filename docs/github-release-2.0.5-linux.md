# Minis Ultra 2.0.5

versionCode **205**，包名 `com.openminis.linux`。

## 权限升级兜底

- 从 2.0.4 及更早版本升级上来的会话一律默认**审批**。
- 新会话默认审批。只有用户在本会话菜单里显式打开 YOYO 才全授权。
- 启动时清掉旧的全局五档工具模式，避免「全部允许」带到新闸门。
- 空白 / 旧 `READ_ONLY` `PLAN` `DENY_ALL` 权限列启动后回填为审批。

## 兼容性

- 包名、会话数据库、Provider HTTP 协议、JNI、沙箱和备份格式保持兼容。
- 可在已安装 2.0.4（versionCode 204）的同签名设备上直接覆盖升级。

## 验证

- 升级默认审批解析单测已更新。
- 云端 release workflow 负责生成 arm64-v8a APK。
