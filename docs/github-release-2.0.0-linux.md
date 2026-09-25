# Minis Ultra 2.0.0

versionCode **200**，包名 `com.openminis.linux`。

2.0.0 是 Android 架构边界与稳定性版本：在保持会话数据库、JNI、沙箱、MCP/Offload JSON 和 Provider HTTP 协议兼容的前提下，完成应用装配、Provider、宿主能力、协程生命周期、Gradle 模块和 CI 构建链治理。

## 架构

- `MinisApp` 改由 `AppContainer` / `AppGraph` 装配仓储，保留安全模式与 nullable repository 兼容语义。
- 应用后台任务统一进入可关闭的 `AppCoroutineScopes`；清除 `GlobalScope` 和业务 `lateinit`。
- 新增独立 `:provider:api` 与 `:hostcapability:api` 模块，保留现有 Provider 与 Native Offload 对外契约。
- 新增 `:core:common` 和 `build-logic` 约定插件，形成真实 Gradle 编译边界。
- 云构建拆为 native dependencies、sandbox assets 与 APK 三段；APK 阶段不再安装 Go 或执行 `gomobile init`，所有资源仍完整内置 APK。

## 稳定性

- 超大会话初次恢复改为有界尾窗和数据库向前分页，避免数千条消息三份全量驻留导致 Scudo OOM。
- 标题、搜索、Evolution、Debug RPC、备份和会话复制使用投影、定点查询或分页。
- OpenAI 标准非流式响应与兼容网关 JSON fallback 恢复一致，合法空 choices 不再误判为断流。
- Retry-After 支持完整指数退避与容错 HTTP 日期。
- 终端 CR 覆盖按真实终端缓冲区语义处理，并保留合法水平空格。
- 修复 Unicode 标点下纠错指纹不稳定、WebView 商店 URI 可测试性与 Anthropic OAuth 测试注入边界。

## 启动器图标

- 启用全新的液态玻璃 `>_` 终端图标。
- 包含浅色、深色、圆形及 Android 13+ 单色主题图标。
- 「设置 → 外观 → 应用图标」强制浅色/深色切换保持可用。

## 验证

- 全量 **1663** 项 JVM 单元测试全部通过。
- GitHub Actions 分段构建 native、sandbox assets 与 release APK。
- Android 16 真机完成覆盖安装、冷启动、供应商调用与崩溃检查。

## 安装

允许「安装未知应用」后侧载 APK。签名不同的安装包不能互相覆盖；必要时先备份数据再卸载旧签名版本。
