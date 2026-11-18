# 长跑记忆的三项后续补齐（2026-11-19）

> 承接 `context-memory-adversarial-review-2026-11-19.md` 的“保留取舍”清单。
> 本轮把三项后续做完：跨进程 working-memory handover、单轮内 assistant 步切分、失败记忆近似聚类。
> 全部带回归测试；本地验证口径见文末。

## 一、WorkingMemoryStore 按会话落盘

### 问题
原实现是进程内 Map：tail budget 逐出未摘要轮次后，若进程在下次 hydration 前被杀，ledger 直接消失，模型重启后看不到任何 handover。

### 设计
- 每个会话一个 `files/working-memory/<safe-session>.jsonl`，一行一条 `Entry`（turn/tool/argsDigest/argsSummary/resultHead）。
- **追加写**：每次工具完成只 append 一行（关闭文件即交给 OS flush，不重写整个文件）。
- **折叠时重写**：`MAX_ENTRIES` 溢出折叠成 1 条 sample 行时，全量重写，避免旧行重放。
- **投递确认**：`renderUndelivered()` 只标记内存 delivered，**不删文件**；调用方 `commitHiddenUserNote()` 把 note 写进 transcript 并拿到 DB id 后，才 `ackDelivered()` 重写文件（只留未投递）。
- **失败回滚**：commit 失败时 `undoDelivery()` 把刚标记的记录放回，下一轮重试；崩溃发生在 commit 前会重投（宁可重复不丢），commit 后未 ack 的窗口会重复一次（已知取舍）。
- **进程重启**：`ledgerFor` 首次访问时读取 jsonl，所有行按“未投递”恢复；已 ack 的记录不在文件里，不会重复。
- **/clear**：`ChatSessionRetention.markExplicitlyEmptied(reason="clear")` 删除该会话的 working-memory 文件与内存 ledger；`/compact` 不删（避免丢未投递记录）。
- **LRU**：进程内最多 128 个会话；被逐出的会话下次访问从文件恢复。
- **坏文件**：逐行 `runCatching`，坏行跳过；`{}`/空行跳过；写入用临时文件 + `Files.move` 替换。

### 测试
`WorkingMemoryLedgerTest`：
- `undelivered rounds survive a process kill`（append → reset 模拟进程死亡 → 仍能 render → ack 后文件消失 → 再 reset 不重投）
- `a failed transcript commit makes the records deliverable again`（undo）
- `a restart delivers only records added after the last handover`
- `a fold rewrites the durable log instead of replaying stale lines`
- `a corrupt durable log is ignored and replaced on the next append`

## 二、postAnchor 轮内按 assistant 步切分

### 问题
`splitRounds` 只按“真实 USER 消息”切轮。一次用户输入 + 300 个工具轮时只有 1 组，`apply()` 直接跳过预算，整段 active region 原样上车。

### 设计
`splitRounds` 增加第二种边界：**当前组已经出现过 assistant 后，再遇到 ASSISTANT 就开新组**。
- 每组从 assistant tool_use 开始，带上其后的 tool_result carrier，配对不拆。
- 有未匹配 tool_use 时**不切**（`hasPendingToolUse`），等 result 落进同组；连续两个 assistant tool_use 也不会拆出孤儿。
- 首组保留该轮 USER 文本 + 第一个 assistant 步；之后每组一个 assistant 步。
- `apply()` 的“最新组必留”不变；被丢的步仍走原 notice + digest。
- 组数从 1 变多后，预算才真正生效；单用户 300 步会被裁到窗口内，新的步仍完整保留。

### 测试
`PostAnchorTailTest.one user turn with many tool steps is split and bounded`：
- `splitRounds` 返回 100 组；
- 预算 4000 时 `droppedRounds > 0`、kept 数 < 原数；
- notice 在首位；kept 的 tool_use/tool_result id 集合相等；`call_99` 必留。

## 三、失败记忆近似参数聚类

### 问题
原 key = `sha(tool + exact args)`；模型把 `cargo build --release` 改成 `--release --offline`、把 `/src/A.kt` 写成 `./src/A.kt`，就被当成全新调用，重新踩同一个坑。

### 设计
保留 exact fingerprint 快路径，新增**同工具 + 同错误类 + 参数 token 相似度**的 near 聚类：
- `canonicalArgs`：先做 secret masking，再取 JSON 标量参数，按非字母数字切成 token（`/src/A.kt` 与 `./src/A.kt` 同 token；`A.kt` 与 `B.kt` 不同）。
- `similarity`：Jaccard；阈值 `NEAR_THRESHOLD=0.75`。
- `errorClass`：错误文本小写、去引号/路径/数字后取 180 字；只有同错误类的近似参数才聚成一类。
- `recordFailure`：exact 不存在但 near 类存在时，累加到 near 类（`failures+1`）。
- `lookup`：exact 命中优先；否则找 same tool / 窗口内 / 已 arm / 同错误类 / 相似度 ≥ 阈值 的 entry，返回 `nearMatch=true`。
- `refusalText`：near 分支明确写“very close”，列出 previous / this call，指出“只改 flag/path 没有改变失败原因”。
- `clear`：成功调用清掉 exact + 同工具高相似 cluster（近似方案已被证明可用，不留下过期阻断）。
- 旧 JSON 没有 `argsCanonical/errorClass` 字段时按空处理：exact 仍生效，near 不误触发。

### 测试
`ToolFailureMemoryTest`：
- `a near-identical call is refused after the shape failed twice`（`--release` vs `--release --offline`）
- `a different file in the same directory is not the same shape`（A.kt vs B.kt 不拦；`./src/A.kt` 拦）
- `a success clears the near cluster too`
- 原有 exact/不同参数/过期/成功清除测试保持通过。

## 四、验证

- 定向：`WorkingMemoryLedgerTest`、`PostAnchorTailTest`、`PostAnchorBudgetTest`、`ToolFailureMemoryTest`、`ChatSessionRetentionTest` 全部通过。
- 全量：`:app:testReleaseUnitTest` **6809 tests / 0 failures / 0 errors**（环境性 MockWebServer 用例首次偶发超时，重跑通过）。
- 门禁：architecture / CJK / string refs / announced tools 全绿。
- 已知取舍：
  - working-memory commit 成功但 ack 前崩溃会重复投递一次（不丢，换重复）。
  - near 聚类阈值 0.75 是启发式，极端相似参数可能被拦；可在后续接经验库时替换为向量/编辑距离。
  - `/compact` 不删除 working-memory；`/clear` 删除。
