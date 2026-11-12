# 修复 spawn lane 单次执行契约违背（#330 审查后续）

## 根因

`#330`（`04075eac`）引入 run 级 `ToolExecutionLedger`，承诺「一次调用 = 一次执行 = 一份结果」。但两条执行 lane 的裁决时机相反：

| Lane | #330 实现 | 后果 |
|---|---|---|
| 串行 `runPlannedCall` | `reuse()` 在 `executeTool` **之前**，命中即 `return` | 契约成立 |
| spawn `fanOutSpawnCalls` | 先 `coroutineScope { peers.map { async { executeRunSubAgent(...) } }.awaitAll() }`，**之后**才 `reuse()` / `record()` | 重放的 spawn 已经拉起第二波子代理；`reuse` 只换了展示结果，副作用木已成舟 |

次级缺陷：

- `reuse()` + `record()` 是分离的非原子「查后做」，并发发射同一 key 时会双双落到未命中分支，各执行一次。
- `parallelSubResults` 是为「先执行、后裁决」而生的中间容器；phase-3 只消费 `execution.executedParts`，全仓无其他读者，属死代码。
- replay 的 `tool_use` block 在 phase-1 建成 `PENDING`；旧代码 reuse 前把它翻成 `RUNNING` 后直接 `return`，卡片永远转圈。

触发面：`replayRenamedIds` 只挡同一 stream attempt 内的同 id 重放；compact / host-side state recovery / 跨 turn 重发落在 run-scoped ledger 的兜底窗口，正是 spawn lane 漏掉的窗口。

## 修复（根因级，非补丁）

1. `ToolExecutionLedger.executeOnce(id, name, argsSha, onReuse, produce)` 成为**唯一执行边界**：
   - `ConcurrentHashMap.putIfAbsent` 原子索赔 `(id, name, argsSha)`；
   - 已 `Completed` → 返回记录结果，不调 `produce`；`InFlight` → `await` 属主 `CompletableDeferred`，同样不调 `produce`；
   - 属主运行 `produce`（整段副作用 + finalize），发布结果并唤醒等待者；
   - 失败/取消 `remove(key, claim)` 释放索赔，失败不缓存、可重试。
2. 两条 lane 把**整段**副作用塞进 `produce`：串行是 UI 翻状态 + `executeTool` + `ToolFailureMemory` + `finalizePlannedCall`；spawn 是 `executeRunSubAgent` + `finalizePlannedCall`。裁决一律在副作用之前，结构上再写不出「先执行后查账」。
3. 删除生产代码里所有分离 `ledger.reuse()` / `ledger.record()`，删除死容器 `parallelSubResults`；barrier 仍 `awaitAll()` 所有 peer，`finalize` 的共享写本来就在 `synchronized` 内（并发串行读一直这么跑）。
4. 顺带修 replay UI：`settledReplayStatus()` 纯映射 + 两条 lane 共用 suspend `settleReplayedCall()`，replay 卡片按记录结果落定 `SUCCESS`/`FAILED`，不再停在 `PENDING`/`RUNNING`。`onReuse` 升为 suspend 以支撑 Main 线程刷新，Completed 与 InFlight 两条复用路径都触发。

三体验：用户（卡片终态、无重复子代理波次）、开发（单一契约入口、死代码清除、KDoc 说明约束）、Agent（重放拿到同一份结果，transcript 与 UI 一致，不产生幽灵执行）。

## 回归验证

- `ToolExecutionLedgerTest`（8 例）：replay 只执行一次 / 跨 name-args 独立 / 首写不可覆盖 / 并发 in-flight 去重 / 失败可重试 / replay 终态映射 / onReuse 双路径触发 / 未记录不复用。
- `scripts/ci/check_tool_execution_contract.py`：结构棘轮（两条 lane 必须走 `executeOnce`、禁止分离 reuse/record、`executeRunSubAgent` 必须在索赔之后、replay UI 落定双 lane 覆盖）+ 纯 Python 状态机复刻（BEFORE 重放触发 2 次 dispatch，AFTER 1 次；跨 key、in-flight、失败重试）。注入回退实验证明门禁能精确 FAIL。
- 门禁挂进 `pr-check.yml` 与 `android-apk.yml`（PR + main push 双覆盖）。
- 本地只跑 Python 门禁（全 PASS）与状态机复刻；Kotlin 编译 / JVM 单测 / 全量 CI 交云端。

## 验证边界

- 本地无 kotlinc，未编译 Kotlin；编译面靠逐条查证（同包 `ToolBlockStatus`、`ToolResult.isError` 全仓既有读法、尾随 lambda 语法、`Deferred.await()` 成员函数、`coroutines-test` 依赖在线）。以云端 `:app:testReleaseUnitTest` 与 `:app:compileReleaseKotlin` 为最终裁决。
- 未做真机/模拟器端到端流式重放；UI 落定由纯映射单测与结构门禁覆盖。
