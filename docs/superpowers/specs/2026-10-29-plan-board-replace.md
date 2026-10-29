# 2026-10-29: 看板工具对齐桌面任务工具（full-list REPLACE + 免 id 定向）

## 起因（用户实测反馈）

1. **模型调用工具经常失败**：`update`/`remove` 要求精确 UUID。模型从快照抄 ID 时
   抄错、抄半截（旧 render 还故意截断显示）、或换用自己编的编号格式，
   "plan item not found" 后模型放弃推进看板，done 项永远不勾。
2. **调用形状反生态**：desktop 任务工具（opencode todowrite、Claude Code
   TodoWrite）全是 full-list REPLACE —— 不带 id、一次调用重建整板，所以
   "标记完成" 从不依赖记住 UUID。我们的 API 要求逐个 id 操作，模型天然写不对。
3. **编号从不被支持**：render 打出 `1. 2. 3.`，模型按编号调用 `update id="2"`
   直接失败 —— 展示的坐标系和调用的坐标系不是同一个。

## 方案

### AgentPlanStore（tools/AgentPlanTool.kt）

- **`PlanInput` + `replace(sessionId, context, incoming)`**：全量替换，incoming
  即整板（顺序 = position）。id 可省略（新铸）、提供且命中旧行则复用身份与
  createdAt；重复 id 拒绝；空白标题拒绝；空列表 = 清板。status 走
  normalizeStatus 别名归一。与 add/update 共用 saveLocked，持久化、version
  信号、finished 时钟全部同路径。
- **`resolveIndex(plans, ref)` 定向阶梯**：精确 id → 1-based 编号（快照打印的
  坐标系）→ 唯一精确标题（大小写不敏感）→ 唯一前缀 → 唯一子串。
  歧义或未命中 → -1，调用方在错误信息里附上实时整板，模型可自纠。
  update/remove 全部改走该阶梯，公共语义一处实现。
- **`normalizeStatus` 别名归一堆**：done/completed/complete/finished →
  done；active/in_progress/inprogress/in-progress/doing → active；
  failed/error/cancelled/canceled/blocked → failed；其余 → pending。
  替代原来"非法状态直接 throw"的语义。
- **错误信息升级**：未命中时输出 `Error: plan item not found for "<ref>"。
  Current board: <整板>`，模型拿到的是"错在哪 + 现在什么样"，而不是死胡同。

### AgentPlanTool.execute

- 新增 `replace` 分支：`plans` 接受 typed JSON array，也接受 JSON 字符串
  （模型塞字符串很常见），两者都走 PlanInput 解析；缺 plans 报明确错误。
- `update`/`remove` 的 id 参数描述改为"id、1-based 编号、精确标题或唯一前缀"。
- 工具 description 重写为 full-list REPLACE 契约 + 触发条件（3+ 步才建板、
  单轮即勾 done）+ 免 id 定向说明。schema 每轮都在，契约随 schema 常驻。

### 快照与空板宣告

- `boardSnapshotPrompt` 头部注入定向提示：`target an item by its number
  (update id="2", status="done"), by its title, or rebuild the whole board
  with op=replace` —— 快照打印的坐标系与调用坐标系在提示词里直接对齐。
- 空板/全完成板的"无 fragment"语义保持不变（T-plan-board-announcement 与
  退休语义），空板宣告仍由工具 description 承担。

### 删除

- `tools/PlanBoardGuidance.kt` 删除：其唯一职责是给空板快照补一段引导文案，
  该引导已并入快照头部 + 工具 description，独立文件不再有存在意义。

## 语义不变

- add/update/remove/list/clear 原行为、migrate、DONE_LINGER_MS 退休、corrupt
  文件抛错、MAX_PLANS 上限、per-session 文件与锁、version 信号、UI
  （PlanBoardStyle/PlanGoalCard）读取路径全部原样。
- 快照的空板/全完成板 → null 的约定原样；fragment 在动态尾里的位置原样。

## 验收

- 静态门禁：architecture policy / string refs / CJK ratchet / announced
  tools 全绿（删除文件后文件数 1033，无需抬基线）。
- 本地 Python 镜像 `sim_board_replace.py` T1-T16 全 PASS，覆盖别名、编号
  阶梯、全量替换、id 复用、清板、重复拒绝、typed/字符串双通道、快照 null
  语义、description 契约、id 完整往返。
- 单测新增 17 例（store 8 + execute 9 中 replace 相关 6 例已并入），编译与
  单测交 CI。
