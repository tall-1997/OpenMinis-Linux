# 「上下文分解」融合进「Token 用量」——单一入口、单一 sheet

日期：2026-11-13
分支：`feat/merge-context-into-token-usage`
动机：会话「⋯」菜单里「Token 用量」与「上下文分解」两个入口打开两个 bottom sheet，开头渲染同一套 Context Used / Window / % / Max Output 数据，用户要开两个面板才能看全同一件事。

## 1. 现象与证据

- 菜单两个入口：`ChatTopBarSection.kt:580`（settings_token_usage「Token 用量」）与 `:598`（chat_menu_context_usage「上下文分解」）。
- `TokenUsageSheet.kt`（210 行）Context 段：Context Used / Context Window / Context % / Max Output —— 纯 StatRow 数字行，数据源 `viewModel.contextUsage`（真实上报）+ `currentModelContextWindow` / `currentModelMaxOutputTokens`（策略值）。
- `ContextUsageSheet.kt`（239 行）Summary 段：LinearProgressIndicator 进度条 + Used/Window (%) + Max Output —— **同一组数据**（`ContextBreakdown.usedTokens/windowTokens/maxOutputTokens` 同样来自 contextTokensForPolicy 与策略值），外加独有内容：7 类分块占比列表（SYSTEM_TOOLS/SKILLS_MCPS/MEMORY/RUNTIME_TAIL/GOAL_PLAN/HISTORY/PENDING_ATTACHMENTS）+ ±10% 估算说明。
- 两 sheet 各自维护一份 `StatRow`（同名 private composable），`ContextBlockRow` / `labelRes` / `ContextUsageSheetState`（进程级 MutableStateFlow holder，因 ChatScreen/ChatViewModel 顶到 architecture-policy 上限而存在）。
- 字符串：`chat_menu_context_usage` / `context_usage_sheet_title` 在 18 个 locale 各 2 条（en + 17 翻译），`context_usage_note_estimate` / `context_block_*` 保留（融合后仍用）。

## 2. 方案（融合而非并列）

**「上下文分解」作为 TokenUsageSheet 的 Context 段扩展**，菜单只留「Token 用量」一个入口：

1. **TokenUsageSheet Context 段升级**：
   - 保留现有 StatRow（Used / Window / % / Max Output）；
   - 顶部加 `LinearProgressIndicator`（`contextUsage.ratio`，真实上报优先）；
   - 段尾追加分块列表：`buildContextBreakdown()` 的 7 类块按 tokens 降序渲染 `ContextBlockRow`（label + share% + token 数）+ ±10% 估算脚注；
   - 分块数据 sheet 内 `LaunchedEffect` 异步拉取（`buildContextBreakdown` 是 suspend、Dispatchers.IO），加载完成前不渲染列表（不阻塞首帧）。
2. **删除第二入口**：ChatTopBarSection 菜单项、ChatSheetsHost 的 ContextUsageSheet host 块、`ContextUsageSheetState` 整个 object、`ContextUsageSheet.kt` 文件。
3. **字符串清理**：18 个 locale 删 `chat_menu_context_usage` / `context_usage_sheet_title`；`context_block_*` / `context_usage_note_estimate` 迁入 TokenUsageSheet 继续引用。
4. **parity 文档**：AGENT-LOOP-PARITY-2026-11-12.md B6 行更新为单入口描述（Token 用量面板含分块占比）。

## 3. 不变量（不破坏的现有功能）

- `ChatViewModelContextBreakdownExt.kt`（数据侧）与 `ContextBreakdownTest.kt`（单测）零改动——纯 UI 融合；
- TokenUsageSheet 其余段落（Thinking / Tokens / Cache / Agent Loop / Context Saver）原样保留；
- `refreshContextUsage()` 打开时刷新逻辑保留（防 compact 后旧读数）；
- iOS parity：iOS 同为单 sheet 结构（TokenUsageSheet.swift 含 context 段），融合后 Android 反而更贴 iOS。

## 4. 验证

- 本地门禁：check_string_refs / check_new_untranslated / check_cjk_literals / check_architecture_policy 全绿；
- 编译验证交云端 CI（本地只跑 python 门禁脚本）；
- PR 流程：分支 → 提交 → push → PR → CI 绿 → squash 合并 → 验证增量非零 → 删分支。
