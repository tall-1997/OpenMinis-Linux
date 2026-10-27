# 任务进行中切换模型：下一请求即时生效

日期：2026-10-27
状态：已实现

## 起因

看板第 4 条：任务进行中切换模型，要在**下一次 LLM 请求**就用上新模型，而不是等整轮结束。

HEAD 上的行为是另一套语义（`ChatViewModelModelSwitchExt`，`T-model-switch-midrun-stall`）：循环在失败重试决策点发现纪元变了，就直接**放弃本轮**，清掉重试倒计时，并提示 "Model switched — send again to use the new model"。健康路径上则完全看不到切换——`currentProvider` 在循环入口拍一次快照，整轮都用它。两条路都要求用户重新发送。

## 方案

请求边界采纳。`runAgentLoop` 里每个请求开始前（`while (!collectDone)` 循环顶部，任何字节发出去之前）读一次 VM 当前选择：

- **纪元没变**：什么都不做，重试/回退继续在飞行中的 provider 上跑。
- **纪元变了且 provider 已解析**：把循环局部的 `currentProvider` 换成 VM 上的新 provider，**按新模型重建回退链**，重试计数归零，并把 `loopModelEpoch` 推进到新纪元。正在飞的那条流不受影响（它已经在自己的 collect 里），下一次请求走新模型。
- **纪元变了但 provider 没解析出来**：保持现状。`selectEntry()` 先 bump 纪元再解析，条目没凭据时提前返回、VM provider 保持原值；采纳一个空 provider 会让整轮失去模型。

决策是纯函数 `ModelSwitchAdoption.shouldAdopt(requestEpoch, currentEpoch, providerResolved)`，解析（读 VM 状态、重建回退链、打日志）在 `resolveModelSwitch`。拆开是为了决策能被单测和 python 镜像同时断言，而解析必须碰 VM。

回退写回的闸门保留并改了理由：本请求内回退到的模型仍只在**用户没在中途另选模型**时才写回 VM。否则写回会覆盖用户的选择，循环顶部的采纳就无模型可采纳。

## 为什么必须重建回退链

回退链在循环入口按**启动模型**解析一次（`buildFallbackProviders` 从组内当前条目的下一位开始环绕）。不重建的话，用户从组内模型 A 切到 B，下一次请求用 B 发出去，一旦 B 失败，链会从 A 的下一位走起，第一跳就是 A——刚被用户换掉的那个模型。`buildFallbackProviders` 以 `_activeEntryId` 定位起点（`selectEntry`/`selectGroupEntry` 都会更新它），所以用采纳后的 provider 重跑一次得到的就是 B 的链。组外单模型选择的 `_selectedGroupId` 为 null，重建结果为空链，回合留在该模型上，与原语义一致。

## 删除的旧路径

`ChatViewModelModelSwitchExt.kt` 整文件删除：`shouldAbandonForModelSwitch`、`abandonIfModelSwitched`、`abandonTurnForModelSwitch` 三个函数在删除重试分支那一处调用后已无任何引用，保留会留下一套与新语义矛盾的死代码。

## 附带的结构整理

`ChatViewModelAgentLoopExt.kt` 贴着架构策略的行数天花板。为给采纳逻辑腾出位置，把轮内 tool_call_id 去重的三段状态（`dedupeStartCounts` / `dedupeCompleteCounts` / `inFlightRenamedId`）和三个闭包抽成 `ToolCallIdDedupe`（`startId` / `inputId` / `completeId`），行为逐字不变：第一次出现保留原 id，第二次起变成 `<id>-N`，delta 按当前在途的改名 id 路由。三个调用点各改一行。

## 文件

- `ModelSwitchAdoption.kt`（新）：`shouldAdopt` 纯函数
- `ModelSwitchAdoptionApply.kt`（新）：`resolveModelSwitch` + `AdoptedModelSwitch`
- `ToolCallIdDedupe.kt`（新）：tool_call_id 去重状态机
- `ChatViewModelAgentLoopExt.kt`：循环顶部采纳、`loopModelEpoch`/`remainingFallbacks` 改 var、删除放弃分支、写回注释更新、去重调用点
- `ChatViewModelModelSwitchExt.kt`（删）
- `ModelSwitchAdoptionTest.kt`（新，4 例）：纪元不变保持、纪元变化采纳、解析失败保持、多次切换折叠为最新
- `scripts/ci/architecture-policy.json`：基线条目从旧文件名改到 `ModelSwitchAdoptionApply.kt`

## 验收

- 本地 python 镜像 `/var/minis/workspace/sim_model_switch.py` 四例与单测同断言，全过（设备沙箱不跑 Gradle，编译与单测交 CI）。
- 本地四道门禁全绿：architecture policy（1015 文件，66 基线）、string refs、announced tools（21）、CJK ratchet（813 字面量，无新增）。
- 语义不变：回退写回闸门 `ModelSwitchGate.mayWriteBack` 原样，`ModelSwitchGateTest` 不改；回退链的桶去重、凭据判断、OAuth 成员保留全部走原函数。
