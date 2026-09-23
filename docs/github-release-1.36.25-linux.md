# 1.36.25-linux

versionCode **78**。安装包 `minis-ultra-com.openminis.linux.apk`。包名 `com.openminis.linux`，可与官方 OpenMinis 并排安装。

相对 1.36.24-linux。这一版来自 build 8 的沙箱运行时缺陷报告，另加会话隔离与权限闸门的收口。

## android-* offload

- `android-contacts` 以前的授权链最长会等 4 分钟的弹窗，而调用方 25 秒就超时。已经发出的请求无法被 `timeout` 取消，于是每次重试都多留一对线程：报告里进程数由 6 涨到 20。
- 现在每个 handler 都在 20 秒看门狗内跑，超时也必定回包（exit 124 + `handler_timeout` JSON），不会再留线程。
- ASK_ONCE 等待收敛到 15 秒，并给出区分于「策略拒绝」的 `interaction_required`，不会再让 agent 去改设置。
- 失败但零输出时，服务器会补一个 `silent_failure` JSON。以前 exit 77 加空输出和崩溃无法区分。

## offload 临时文件

- 回包文件 TTL 由 10 分钟收到 10 秒。原来的 TTL 恰好让清扫在它本要解决的场景里失效，一个会话内积了 34 个文件。

## 工作目录

- 会话 shell 是长驻的。`cd` 进的目录被删掉后，之后每条命令都在失效 inode 里跑：`pwd` 打印死路径，`ls` 与 `os.getcwd()` 全失败，且不会自愈。
- 现在每条命令结束后都会用 `$PWD` 检查并复位，不必再手工 `cd`。

## 子代理

- 停止一次停在挂起点上的派发会漏掉计数器的回收，之后所有派发都被「sub-agents cannot spawn further sub-agents」拒绝，直到杀掉应用。
- claim 移进覆盖全部后续逻辑的 `try/finally`，守卫改用协程上下文里的 lane 标记，计数器降级为可自愈的陈旧态探测。

## 会话隔离

- `search_sessions`、`read_session`、`minis-sessions-cli search|messages` 都会返回消息正文，但以前不问调用方是谁；`search_sessions` 甚至因为在只读工具集合里而自动放行，任何会话都能读别的会话。
- 现在按目标会话判归属：不是自己的会话必须持有 `session_read` 授权（默认每次会话问一次，可在设置里改）。`search` 没有单一目标，无授权时收窄到调用方自己的会话，回包带 `scope=current_session`。
- `list` 保持开放：只返回 id 与标题，且 agent 得先拿到 id。
- 每次尝试无论放行与否都记审计（只记 id 与动作，不记正文）。

## 权限闸门

- 会话「全部允许」以前会把隔离类拒绝一并降级为放行。现在隔离拒绝（其他会话目录、应用数据库、未挂载客户机路径）是硬拒绝，任何模式都不能降级。
- `su` 路径检查拿到真正的调用方会话，不再连本会话自己的目录一起拒。
- 客户机 `/sdcard` 是否真的挂上了，改由沙箱的 bind 计划回答，不再靠猜。

## 说明

- 删除 `HostCaBundle.kt`：它与 `RootfsManager` 已有的 CA 持久化重复（conf 注册 + `minis-dedup` 钩子），且用的是另一套存储布局。
- 新增 `GuestMountPolicyTest`。

## 安装

侧载前允许「安装未知应用」。debug 签名不能覆盖用另一张证书装上的版本，见 [docs/SIGNING.md](docs/SIGNING.md)。完整说明见 [docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。
