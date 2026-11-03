# M8 / PR-H：P1-2 知识工作台 — 设计文档

- 日期：2026-11-03
- 依据：/var/minis/workspace/implementation-plan-p0p1p2.md 第 89 行 M8 定义
- 状态：spec 定稿，实现按本文件 + TDD

## 背景与动机

长会话 + 多任务并行时上下文窗口折叠，早期决策与勘察结论丢失（本会话在 M1–M6 已实际遭遇）。知识工作台把「用户可见的长期笔记」落进 Room：版本化、可检索、可图谱、可回收，AI 问答带引用校验。与已有 `knowledge/` 包（AppNotes/AppKnowledge —— agent 内部 JSON 笔记，SharedPreferences 层）边界：本模块是用户 UI 层的笔记库，存储层（Room）与消费方（用户）都不同，不复用 AppNotes。

## 勘察结论（已查证，实现以本文件为准）

- **AppDatabase version = 24**（AppDatabase.kt:24），本模块迁移 = 24→25；`DatabaseVersionGuard.CODE_DB_VERSION` 同步。
- **检索不建 FTS5 表**：仓库已否决 FTS5（unicode61 无法匹配 CJK 子串，主场景）。照 ChatSearchDao 模式：`search_text` 列（≤2KB 摘录，长文截断）+ `LIKE '%q%'`。计划书「一期 FTS」= 此模式。
- **schema json**：`src/android/app/schemas/com.openminis.app.data.db.AppDatabase/25.json` 手写提交（在 24.json 基础上叠加 24→25 DDL 效果），`scripts/ci/check_schema_drift.py` 本地重放 DDL 自校验闭环。
- **侧栏入口**：`ui/navigation/ChatSplitScaffold.kt`（会话列表侧栏）加「知识库」行 + `Routes.KNOWLEDGE`。
- **图谱 Canvas 先例**：AudioWaveformView 等已用 DrawScope，图谱页用 Compose Canvas 可行。
- **Apple 风格**：对照 awesome-design-md 的 Apple DESIGN.md + 仓库现有 BackupAndRestoreScreen / ProviderDetailScreen 模式。
- **版本上限**：MessageVersion 归档有先例，笔记版本上限 30 版（超出删最旧），纯函数裁剪可测。
- AppNavigation.kt 1071 行（架构基线内），新 NavGraph 外挂独立文件，AppNavigation 只加调用点（ratchet 只降不升）。

## 数据模型（AppDatabase 24→25）

### KnowledgeNoteEntity（表 knowledge_notes）
- id: String PK（UUID）
- title: String
- bodyMd: String（Markdown 正文）
- status: String（`active` / `trashed`）
- tagsJson: String（JSON 数组，无独立分类表——标签 chips 筛选即分类）
- linksJson: String（[[链接]] 解析出的目标 title 列表，图谱边来源）
- searchText: String（≤2KB 摘录：title + body 纯文本截断，LIKE 检索用，参照 messages.preview）
- createdAt / updatedAt / trashedAt: Long（epoch ms，trashedAt 0 = 未删）

索引：status, updatedAt 复合（列表查询）。

### KnowledgeNoteVersionEntity（表 knowledge_note_versions）
- noteId: String FK → knowledge_notes.id（CASCADE）
- version: Int
- bodyAt: Long
- 复合主键 (noteId, version)；上限 30 版，裁剪 = `DELETE ... WHERE note_id=:id AND version NOT IN (SELECT version ... ORDER BY version DESC LIMIT 30)`

### 无独立 search 表
search_text 列内嵌（参照 messages.preview 模式，不另建 FTS 影子表）。

## Repository（data/repository/KnowledgeRepository.kt）

- `importNote(title, bodyMd, tags, sourceHash)`：**去重** = sha256(title+"\n"+bodyMd) 前 12 位 hex；已有同 hash 的 active 笔记 → 返回 ImportOutcome.Duplicate（不写库）。新增时：解析 [[...]] → linksJson，建 searchText，同时归档 version 1。
- `upsert(note)`：写 updatedAt、重建 searchText/linksJson、归档新版本（裁剪 30）。
- `trash(id)` / `restore(id)` / `purgeTrashed(id)`：status 与 trashedAt 维护；purge = 真删（版本行 CASCADE）。
- `archiveVersion(noteId, bodyAt)`：INSERT 后裁剪，返回是否发生了裁剪（供测试）。
- `search(query)`：`WHERE status='active' AND search_text LIKE :pattern ORDER BY updated_at DESC LIMIT 200`（%q% 由调用方拼）。
- `listByTag(tag)`：tagsJson LIKE 匹配（`%"tag"%` 风格，tagsJson 存 JSON 数组字符串）。
- `graphEdges()`：全部 active 笔记的 linksJson → 边集（纯函数 `KnowledgeGraph.edgesOf(notes)` 提取，Repository 只供数据）。
- `citationSegmentsFor(query, topK)`：检索 top-K 笔记 → 段列表（编号 [1]..[k]，含 title + 摘要），供 AI 问答 prompt 组装。

## 纯函数（knowledge/KnowledgeNoteGraph.kt，TDD 核心）

- `parseWikiLinks(bodyMd): List<String>`：`[[标题]]` 全量解析，去重，忽略空标题。测试：单/多/无/去重/换行内联。
- `edgesOf(notes): List<GraphEdge>`：仅保留「指向已存在笔记 title」的边（图谱不画幽灵节点）。
- `forceLayout(nodes, edges, iterations, width, height): List<LayoutNode>`：力导向纯函数（斥力 + 弹簧边力 + 中心引力 + 边界钳制），确定性输出（固定随机源或全确定性），测试：两节点距离收敛方向、无边节点散开不重叠。
- `buildSearchText(title, bodyMd, cap): String`：截断规则可测（>2KB 截断、含 title）。
- `dedupeHash(title, bodyMd): String`：sha256 前 12 位。
- `CitationVerifier.verify(replyText, segmentCount): CitationCheck`：提取 `[n]`/`[n,m]` 引用编号 → 三分支：OK / OutOfRange（编号 > segmentCount）/ None（无引用）；OutOfRange 时生成纠正提示串（发给模型重答）。测试：越界 [5]（segmentCount=3）、合法 [1][2]、无引用、混合越界。

## UI（ui/knowledge/，Apple 风格，先加载 awesome-design-md + ui-ux-pro-max）

- `KnowledgeNavGraph.kt`：独立 NavGraph（外挂 AppNavigation，行数对冲）。
- `KnowledgeListScreen.kt`：列表 + 搜索框 + 标签 chips + FAB 新建 + 滑动/长按删除（入回收站）。
- `KnowledgeDetailScreen.kt`：标题/Markdown 预览（复用仓库 markdown 渲染组件）/标签编辑/版本历史入口/「问 AI」输入条。AI 问答：检索 top-K 段编号 [1]..[k] 拼 prompt → 回答后 CitationVerifier 校验 → 越界自动带纠正提示重问一次 → 展示最终回答 + 来源段列表。
- `KnowledgeGraphScreen.kt`：Canvas 力导向（forceLayout 消费），节点 = 笔记（大小随入度），边 = [[链接]]；点击节点进详情。
- `KnowledgeTrashScreen.kt`：回收站列表 + 恢复/彻底删除。
- 入口：ChatSplitScaffold 侧栏「知识库」行（图标 + 文案）。
- 翻译：新增 key × 17 locale（与既有 locale 清单一致）。

## 迁移与门禁

- `MIGRATION_24_25`（AppMigrations.kt）：CREATE 两张表 + 索引；DDL 字面量字符串（门禁解析要求）。
- `25.json` 手写：格式照 24.json；本地跑 `check_schema_drift.py` 必须零漂移。
- 四道门禁全绿 + kotlinc 单文件语法审计（syntax/unclosed/missing 过滤零真错）+ 嵌套注释审计脚本。
- 架构 ratchet：新文件各自 < 上限；AppNavigation/ChatSplitScaffold 只加调用点，基线只降不升。

## 测试计划（先写测试再写实现）

1. `KnowledgeNoteGraphTest`：parseWikiLinks / edgesOf / forceLayout / buildSearchText / dedupeHash / CitationVerifier 全部路径。
2. `KnowledgeRepositoryTest`（Room in-memory）：导入去重（同 hash 拒写）、版本归档上限 30 裁剪、回收站三态、search LIKE、graphEdges 只含存活边。
3. `KnowledgeMigrationsTest`（Room MigrationTestHelper 惯例）：24→25 迁移后表结构断言（列齐全）。

## 验收清单（PR 描述用）

- [ ] 侧栏入口可进知识库；列表/详情/图谱/回收站四屏可用
- [ ] 导入去重：同内容二次导入被拒并提示
- [ ] 版本历史保留 ≤30 版
- [ ] AI 问答引用越界自动纠正（CitationVerifier）
- [ ] 图谱只画指向真实笔记的边
- [ ] 四道门禁 + CI 绿；25.json 与 DDL 零漂移
