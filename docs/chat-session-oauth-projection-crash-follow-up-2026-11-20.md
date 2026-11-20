# 会话打开闪退：OAuth 上下文预估回归（2026-11-20）

## 现场与根因

用户反馈点击进入会话闪退，并提供 2026-11-20 的设备日志。两次崩溃均为：

- 版本：2.0.97.402；设备：Redmi 25102RKBEC / Android 17。
- 异常：`IllegalStateException: ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured`。
- R8 mapping 将 getter 调用映射到 `ChatViewModelMemoryDeliveryExtKt.wrapMemoryDeliveryPromptForOAuth`。
- 当时公开构建的 `provider-customization.properties.example` 将该 OAuth 专用值留空；日志所处路径是会话加载结束后启动的请求上下文预估，不是 OAuth 请求发送。
- 日志显示会话已读 501 条（总计 2383 条，约 527871 字符），堆使用约 31/512 MiB，首帧进度约 22 ms；证据不支持 OOM 假设。

直接根因是 prompt wrapper 在确认 provider 是 Anthropic OAuth 之前就读取了会抛异常的 BuildConfig getter。普通 Anthropic API-key 会话因此也触发 OAuth-only 配置要求。异常从 `loadSession` 启动的异步 context estimator 未捕获地逸出，进而导致应用崩溃。

## 漏检复盘

之前的对抗审查和验收没有覆盖“公开构建 OAuth 前缀为空 + 打开 Anthropic API-key 会话 + after-paint 后台请求投影”这一组合。纯 provider 请求测试未执行 `ChatViewModel.loadSession` 的异步投影；前缀偏移相关测试也没有验证配置读取是否发生在 provider 身份判断之后。因此当时的全量 CI 和审查没有触达实际崩溃路径。

## 修复与保护

- OAuth 前缀配置改为可空读取；只有真实 Anthropic OAuth provider 才惰性读取该配置。API Key 和其他 provider 的预览/估算不读取 OAuth metadata。
- 配置缺失时，预览保持可用；真正构造 OAuth provider 请求时，返回带配置说明的 `LLMError.ProviderError`，并在 HTTP 请求发出前失败。
- prompt 前缀与稳定缓存边界、每日记忆 slot/spans 一起移动；仅识别完整 `identifier + 双换行` 前缀，避免重复添加或误认普通文本。
- 会话上下文估算作为可选工作单独隔离普通异常，失败时不发布不完整估算并写 warning；协程取消继续传播。加载后的 receipt reconciliation 与 projection 在同一保护边界内。
- 新增 provider、projection、取消传播、估算失败隔离测试，以及基于 Robolectric、Room 和真实 `ChatViewModel` 的会话打开回归。真实回归使用 Anthropic API-key binding，并等待实际上下文估算发布；不依赖本机构建的 OAuth metadata 值。缺失配置由显式注入 null/空白值的单测覆盖。

## 验收结果

- 定向 Release：AnthropicProviderTest、OAuthPromptProjectionTest、SessionContextEstimateTest、SessionOpenProjectionTest 共 **47 项通过**。
- 最终 Release 全量：`:app:testReleaseUnitTest --parallel --stacktrace`，**7,206 项，0 失败、0 错误、4 跳过**；`BUILD SUCCESSFUL in 2m 29s`。
- 九项源码/结构门禁通过：schema drift、字符串引用、CJK ratchet、ThinkingLevel UI、架构 ratchet、tool execution contract、tool title/budget、announced tools、新增翻译检查。
- MCP stdio bridge **16 项通过**，并通过 stdio 资产一致性检查。
- 独立只读复审最初发现一项私有构建兼容性问题：session-open 用例错误要求 OAuth metadata 为空。已移除此环境前置条件；复审未发现生产代码缺陷。
- 未运行设备安装/实机 UI 回归；Robolectric 用真实 `ChatViewModel`、Room 和 provider repository 覆盖 session-open projection。
