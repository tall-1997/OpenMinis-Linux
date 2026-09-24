# 审计交接（2026-09-24 21:30 前）

产品名 Minis Ultra。不要改 CLI 名，不要改 `/var/minis`。不要抽 `:provider`、`:sandbox`、`:core:model`，不要拆 `ChatViewModel.kt`。不要把任务清单里的 Gradle 缓存、CI、benchmark、`shrinkResources`、`StreamSessionController` 抽离当成未完成工作，除非某条仍生效的说明明确要求。后写说明覆盖先写。仓库没有 1.36.15、1.36.29–1.36.33 的说明，不要臆造。「本版未接」和明确延期不是工作。没有核实到的行为缺口不要改版本。

本交接写于 1.36.50-linux / versionCode 103 的修复提交里。`:app:compileDebugKotlin` 与 `VideoModalityTest`、`SessionForkCopyTest` 已离线通过。若该提交已推送，远程 tag 是 `1.36.50-linux`。下一轮只在又核实到新缺口时才升到 1.36.51-linux / versionCode 104。

## 本轮已修（1.36.50）

1. 目录没给模态时，视频/生图 id 会先推断再补缺省文本。`applyUnrecognizedModelDefaults` 以前先写成 `["text"]`，`withInferredVideoModality` / `withInferredImageModality` 看到非空就跳过。已存成 256k / 128k / 纯文本、且用户没有改过输出模态的，读取时会剥掉这层缺省再推断。目录自己给了模态，或上下文不是这组缺省值的，不覆盖。
2. OpenAI / Anthropic 的 RAW SSE 只在 `log.tag.ToolChain[Provider]` 为 VERBOSE 时打印。`"error"` 不再绕过这个门。
3. 会话列表复制改走 `SessionForkManager`，并拷当前挂载的工作区（已归档会话拷项目目录）和该会话记忆。副本不进同一分组，避免和原会话共用项目树。

归档冲突不是缺口。1.36.17 写明私有子目录已有文件时不覆盖、不把该会话当成可独立于项目。`WorkspaceMoverTest.nonEmptyDestinationIsNotDeleted` 锁着这个行为。

## 已对上、不要再当缺口

1.36.16–1.36.49 里已经逐行对过的包括：启动顺序 overlay → 时区 → 镜像配置 → minis-mirror auto → seed → dpkg retry → pip retry；工具上限返回箭头；一级设置无箭头；MCP 工具开关；提供商置顶与并行强制刷新；时区相对链接；技能 requirements 的 env/tiers 为 Map 且 apt 优先于 apk；原子写（路径锁 + 临时文件 rename + 回读）；共享存储进 shell_execute；startForeground 失败不崩；minis-config 可读 security.permissionMode；世界书只在最近消息命中时注入；显示正则不改存档；权限模式只在权限页；tool-limits 深链接；total_memory_mb 是整机内存；TZ 用时区名；write_paths=none；proot info 噪声；handler_timeout 退出码 124；Termux terminal-view 0.118.0；dpkg/pip 快照与最多 3 次、apt `--no-upgrade`；键盘 120dp 与长文编辑 imePadding；视频路径先 `/v1/videos` 再宿主根；字符串 passthrough 与顶层 endpoint；翻译写回；高刷新率只写一次；通知 IMPORTANCE_LOW、1.5s / 30s；OCR 长边 1600；explore/plan 拒绝 `2>文件` 但放行 `2>&1`；镜像中文对照；minis-open 无 TTY 仍发 OSC。

1.36.1–1.36.8 抽查已对上的包括：缺参补 256k/128k、步进器、强 429、限流桶、组内换人、压缩不拆段、视频生成路径（在模态判断之后）、浏览器底栏、检查更新、网页搜索、无障碍 awaitA11yEvent、shrinkResources。1.36.9–1.36.14 抽查已对上的包括：项目共享与记忆仍按会话、删会话不删项目树、人格绑定不入库、密钥不进沙箱、更新续装、折叠增量收起。

## 下一轮要逐行对的

这些还没有用文件行号证明，不能记成已对上，也不能直接改：

- 1.36.17：移出/解散分组的拷回、冲突不删项目、单个「新建文件夹」FAB。
- 1.36.18：主机 CA 是拷贝不是符号链接；`/dev/tcp` 快筛后的临时 `sources.list`。1.36.19 已覆盖「拿不到锁就重试」，轻量脚本忙锁应 exit 0，不要改回重试。
- 1.36.19：轻量/完整 `minis-dev-setup` 包清单，以及 `trap … exit 143`。
- 1.36.20：证书 0755/0644、OpenSSL `.0`、`SSL_CERT_FILE` 写入 `/etc/environment`。
- 1.36.21：流式结束后才重新聚焦；内置 SOUL 不可删除；子代理单个 Error 不取消同批；保留 `org.json`；自编译未编译成功不报成功。
- 1.36.22：NDK 包名 `android-ndk-r29-aarch64.tar.xz`、压缩包三种格式、主机 CA 双目录与 `minis-ca-dedup`、脚本不再 `Verify-Peer=false`。
- 1.36.23：空目标搬移失败不藏私有文件；会话路径被拒后不再改读全局绑定。读图和模型调用已经按这条拒绝回退，不要改回去。
- 1.36.25：隔离拒绝不可被「全部允许」降级；`su` 使用真正调用方会话。
- 1.36.1–1.36.8 未追完：`models-dev-api.json.gz` 失败是否回退明文；聊天气泡能否播放 `minis://attachments/generated/*.mp4`；点停止后视频阻塞请求是否立刻断开。`:core:model` 迁移和 CI 归档不要当成缺口，除非说明仍要求且后文没取消。
- 1.36.36「一批界面文案补了中文」没有清单，不要为了凑数改文案。
- 启动图标缺 XML inset：没有像素测量之前不是缺口。

## 编译和推送

本地编译临时加 `buildToolsVersion = "36.1.0"`，提交前必须拿掉。命令在 `src/android`：

```
java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin --offline
```

不要提交 `src/android/.kotlin/`，不要提交临时 pin。不要 force-push。推送用 `C:\Users\yp\AppData\Local\Temp\apipush.bat`，先 `--check`。有版本修复时再带 `--tag` 和 `--expect-parent`。父提交以当时的 `git rev-parse HEAD` 和远程 `713b0d261673ee7d67912e57804e6a817fefe790`（1.36.49-linux）为准；若 1.36.50 已推送，下一轮的 expect-parent 改成 1.36.50 的完整哈希。
