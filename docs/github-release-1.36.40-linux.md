# Minis Ultra 1.36.40-linux

versionCode **93**。包名 `com.openminis.linux`。产品名仍是 Minis Ultra。

## 本版

- `minis-model-use` 把字符串形式的 `passthrough`，以及顶层 `endpoint` / `endpoint_path` 加 method 或 body，都当成原始请求。不含 `/images/` 的路径不会再被改写成 `/images/generations`。视频模型不再落到 chat/completions。
- 视频生成先走和图片同一套 `/v1` 路径。宿主根路径 `/videos` 不再第一个尝试，避免同一把密钥在错误网关上被报成 Invalid API key。
- `minis-model-use run` 接受 `run` 后面的位置参数。没有用户内容时退出码是 2，不再返回固定的 `input_tokens=12`。成功结果带 `sent_user_chars`。原生 offload 不转发 stdin。
- `multi_edit` 接受字符串、单个对象、`replacements` / `changes`，以及顶层的 `old_string` / `new_string`。
- 定时任务写入改为同步 `commit()`。删除支持唯一 id 前缀，并回读确认。相同标签、时间和提示的重试不会再造出第二条。
- DuckDuckGo 没有结果时，改用无需密钥的 Wikipedia OpenSearch、Bing HTML 和 Mojeek。
- 混合命令里只有碰到未挂载客户机路径的那一段被改成失败，其余 `;` / `&&` / `||` / `|` 照常执行。整段都越界时仍然拒绝。
- 会话列表定时任务角标收进顶栏测量范围。长文件名在会省略时改用导航和操作按钮之间的真实空隙。
- `android-notification clear` 默认只取消本应用自己的通知，并报告数量。清空其它应用必须显式加 `--all-apps`。
- `execute_code` 的工具函数接受字符串参数，并把它填进对应的主字段。
