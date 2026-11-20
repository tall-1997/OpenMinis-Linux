# #401 合并对抗审查与修复报告（2026-11-20）

对象：`feat/roadmap-context-engineering-2` 上 `git merge --no-commit` 的候选树。
两条父线：本地路线图 `47d8e5e7`（十项落地）× 远程 `dc473389`（#401）。共同基线 `93a8f2c3`。
原则：兼顾远程新特性、保留本次开发目标；只修根因，不抬基线、不删有效测试。

## 第一轮：合并冲突域 + 全量失败定位

| # | 等级 | 问题 | 位置 | 修法 |
|---|---|---|---|---|
| R1 | P2 | 队列失败恢复用例用 `assertSame` 断言跨协程异常同一性，导致顺序断言未执行（全量唯一失败） | `ChatViewModelDrainQueueTest.kt:83` | 改为类型+消息断言，保留 `before, after` 顺序检查；重跑通过 |
| R2 | P1 | Stop 在工具对未提交时独立写孤儿取消结果，压缩覆盖门永久卡死 | `ChatViewModelStopRunExt.kt` / `CompactSliceBoundaries.kt` | Stop 改为部分回复+宿主中断说明同笔原子写；旧行按严格 sentinel 投影为文字，普通孤儿仍止损 |
| R3 | P2 | 大正文 BodyStore 写失败仍返回省略 stub，两行落库后内存 parts 被对账成"已验证" | `ChatRepositoryTurnRows.kt` | `requireOffload=true` + `verifyExact`，坏缓存整批拒写；Room 故障注入回归 |
| R4 | P2 | `history_search/history_read` 漏出只读豁免清单，同批次第二次读取被回执锁住 | `ToolReceiptDispatch.kt` | 加入豁免，SecurityGate/sid 不变 |
| R5 | P2 | 历史检索把持久 stdout 预览当全文（`truncated=false`），中段关键词永远搜不到 | `HistorySearchTool.kt` / `ChatRepositoryHistory.kt` | `preview_only` 标记贯穿搜索/分页；不新增宿主文件读取授权 |

## 第二轮：次生问题与运行边界

| # | 等级 | 问题 | 位置 | 修法 |
|---|---|---|---|---|
| S1 | P1 | 冷开时容量门用 DB 最新 marker 判覆盖，发送却挂 UI 缓存旧 marker，可发未摘要历史 | `ChatRepositoryCompact.kt:79` / `ChatViewModelDispatchProjection.kt` | `requestCompactState` 同一 Room 事务返回 marker+gap，实际投影显式传入 |
| S2 | P2 | 实测总量减本地粗估提醒成本，差值冒充"实测稳定锚" | `ContextUsageAnchors.kt` | 带临时提醒的请求只校准不落锚；指纹绑定出站遮盖与有效思考档位（v3） |
| S3 | P2 | 隐私遮盖在 provider 内做，锚却按未遮盖文本算；开关切换后复用旧实测 | `LLMProvider.kt` / `RequestProjection` | 共享 `projectOutboundRequest`；`expectedOutboundMask` 不符即抛错重建投影 |
| S4 | P1 | 慢 IO 历史重建先清 resident 再等 DB，旧 run 迟到会覆盖新会话历史 | `ChatViewModelAgentHistoryExt.kt` | `publishOwnedHistory`：读完→归属校验→Main 一次性发布；失败不清空 |
| S5 | P2 | UI 批处理只在正常退出停止；取消/异常路径消费者泄漏、复活已收尾气泡 | `UiUpdateBatcher.kt` / `RunLoopGate` | `withRunUiBatcher` 结构化持有，finally 必停；push 前复查 tombstone |
| S6 | P2 | 生成收尾按会话集合关闭，旧 run 会把同会话新 run 标 FAILED | `GenerationRunLifecycle/Store` | 按 runId 精确关闭，`finish` 幂等且只动 RUNNING |
| S7 | P2 | 发送前压缩成功一批即 `skipContextCheck=true`，剩余缺口直接停发 | `ChatViewModel.kt:2624` / `SendExt` | 拆分 `skipInitialCompaction`；成功续压仍走有限补覆盖 |
| S8 | P2 | 工具调用在 DB 提交前就进 resident 历史；取消窗口留半轮 | `ChatViewModelToolTurnPair.kt` | 完整事件驱动 `completedToolTurnParts` + 身份校验；两行提交成功后一起发布 |
| S9 | P2 | 预取命中仍同步重扫全库 BM25；向量失败误用 30s 有效期；取消被吞 | `MemoryRecallEngine.kt` | 有界混合候选+目标复验；失败 5s 退避；取消不回填不发布 |
| S10 | P2 | 正文缺失/损坏时搜索报"全覆盖零命中" | `HistorySearchTool.kt:196` | `unavailable_rows` + `truncated=true` + coverage_note |
| S11 | P1 | `loadRequestHistory` 一次装 4000 行×最大 8MiB，OOM 风险；坏正文退 stub | `RequestHistory.kt`（新） | keyset 分页 + 16MiB 预算 + 分块重组 + `readExact`；不可恢复抛 `RequestHistoryUnavailable`，UI 显示错误并中止 |
| S12 | P2 | v1 marker 切开旧真配对后 repair 删除取消含义；早期投影破坏多取消 carrier | `ChatViewModelAgentHistoryExt.kt:126` / `RequestProjection` | `projectLegacyCompactHistory` 保闭合边界；取消投影移到 assembled 之后、scrub 之前 |
| S13 | P2 | Resume 只认 assistant 尾；新 Stop note 尾无法续跑；停止说明被当成继续指令 | `ChatViewModelResumeExt.kt` | 识别 `USER_STOP_NOTE` 尾；继续指令仅在用户点 Resume 后写入 |
| S14 | P2 | 宿主备注用单行 append，无写前归属复查 | `ChatViewModelHiddenNote.kt:167` | 改走 `appendTurnRows(ownsWrite=::ownsNote)`，事务插入前再验 |

## 第三轮：收口复核

| # | 等级 | 问题 | 修法 |
|---|---|---|---|
| T1 | P2 | 双配对检查 `earliest` 相互覆盖，canonical 轮可把边界移回 wire孤儿之前 | `maxOf` 聚合 + Room 回归 `canonicalPairingCannotMoveTheBoundaryBackBehindAWireOrphan` |
| T2 | P3 | `completedReply` 只看可见内容，refusal/无终止帧也触发 RESPONSE 钩子 | 复用 `eligibleContextReceipt` 判定 |
| T3 | P3 | DAO 残留仅测试使用的 `SELECT *` 旁路（全量 ResourceBoundaryTest 失败） | 删除 `loadMessagesTailRaw`，测试改走正式有界回读 |
| T4 | P3 | `StopTranscript.kt` 缺 `JSONObject` 导入（专项编译失败） | 补导入 |
| T5 | P3 | 20 个文件行尾空白/EOF 空行 | 统一清理，`diff --cached --check` 归零 |

## 验收（同一最终树）

- 专项：20 套件 **170/170** 通过（含 Room 34→35 真迁移、坏存储、Stop 重开、队列顺序、预取竞态）。
- 全量：`:app:testReleaseUnitTest` **7193 项，0 失败 0 错误，4 跳过**，`BUILD SUCCESSFUL in 7m 7s`（空白清理后的最终树重跑确认）。
- 九门禁：schema 重放（12→35）、字符串引用、CJK 收紧（16→15）、思考显示、架构收紧（VM 4328→4270、Loop 1832→1791、Repo 1383→1354，ChatDao 799≤800）、执行契约、标题/预算、工具声明、新增翻译（候选树 vs origin/main：无新增键）。MCP 桥接测试+资产一致性另过。
- 设备端：本机 release.keystore 无密码，`connectedReleaseAndroidTest` 无法签名安装；34→35 以 Robolectric 真 SQLite 迁移覆盖，**未冒充设备实测**。
- 远程：提交前两次 fetch，`origin/main` 始终 `dc473389`，无前移。

## 已知取舍（非缺陷）

- 滑窗外极旧轮去重不永久；无可信 finishReason 不结算用量锚；歧义退出码不自动重派。
- 历史读取遇不可证明的旧歧义配对选择 fail-closed（报错而非猜测）。
- `.zcode/` 会话计划保留在工作区，不入提交。
