# 设计：模型删除 / 参数优先级 / 备份记录 / 恢复合并 / 工具 ID / 列表跳动 / 悬浮胶囊 / Markdown 链接

日期：2026-10-21。七项独立改动，每项先写失败测试再改实现。

## 1. 长按删除任意模型（含 API 下发）

**根因**：`ProviderDetailScreen.kt:695` 删除菜单项被 `if (entry.isCustom)` 包住；
`ProviderRepository.replaceEntries`（1004 行）刷新时按 `baseModel.id` 重建非自定义条目，
所以即使删掉，下次刷新也会以 `isCustom=false` 重新出现。隐藏（isHidden）能跨刷新存活，
删除不能，因为没有任何持久记录。

**方案**：
- 新增纯函数 `ModelDeletion`（core/model 模块，可单测）：
  `tombstone(entries, instanceId, entryId)` 返回被删条目的 `baseModel.id`（自定义条目返回 null，
  自定义条目按 uuid 删除即可，刷新路径本来就保留自定义条目）。
  `applyTombstones(models, tombstonedIds)` 从刷新结果里剔除被标记的模型 id。
- `ProviderRepository` 新增 `deletedModelIds: Map<instanceId, Set<modelId>>`，随 ProviderConfig 持久化
  （新字段，默认空，旧数据兼容）。`removeEntry` 对非自定义条目写入墓碑；`replaceEntries` 与
  `refreshModels` 应用墓碑；用户手动「添加自定义模型」同 id 时清除墓碑。
- UI：删除菜单项对所有条目开放，确认对话框沿用现有 `entryToDelete` 流程。

## 2. 端点参数优先级：返回参数 > models.dev > 兜底 256k

**根因**：`ModelsDevApi.applyDevData`（414 行）无条件用目录值覆盖模型自身字段，而 `/v1/models`
解析出的 context/output 正是写在模型自身字段上的。

**方案**：
- 新增纯函数 `ModelParamPriority.resolve(endpointContext, endpointOutput, devContext, devOutput)`，
  每字段独立取值：端点值（>0）优先，其次目录值，最后 `UNKNOWN_CONTEXT_WINDOW`(256_000) /
  `UNKNOWN_MAX_OUTPUT`(128_000)。
- `applyDevData` 改为：模型自身字段非空时保留（端点值已在其中），目录只补空缺。
- 兜底层 `applyUnrecognizedModelDefaults` 保持不变（只填空缺）。

## 3. 备份记录不再堆积进度行 + 旧记录清理 + 原子写入

**根因**：`BackupViewModel.deliverToRemotes`（539 行）每个百分比回调一次 `onProgress`，
`publishRunning` 把每行追加进 `Record.log` 并整文件重写。本地文件夹拷贝大文件时产生上万行，
历史页一次性加载全部记录卡死。`BackupHistory.persist` 用 `writeText` 直接覆盖，崩溃留半截 JSON。

**方案**：
- 新增纯函数 `BackupLogCompaction.compact(entries, maxLines)`：连续的进度行（消息匹配
  `^(Sending to .+…|Copying to .+…)`）只保留最后一条，问题行与普通行全部保留，总量再截断到
  `maxLines`（200）保留尾部。
- `publishRunning` 写入前先 compact。
- `BackupHistory.records()` 加载时对每条记录 compact，若有变化则回写（一次性清理旧记录）。
- `persist` 改为写 `records.json.tmp` 后 `renameTo`（原子替换）；解码失败时保留原文件并记日志。

## 4. 恢复以备份为准

**根因**：`ProviderBackupCoordinator.mergeBackupProviderConfig`（333 行）同 id 取本地
（`existing ?: ri`），分组只按 id 对齐，同名不同 id 的分组会重复；计数 `after - before`
只算新增服务商数。

**方案**：
- 新增纯函数 `BackupMerge.merge(local, remote)` 返回 `(merged, changedCount)`：
  - 服务商：同 id 用备份内容整体替换（含凭据字段），本地独有的保留并追加。
  - 模型条目：同 id 用备份内容替换（参数恢复），本地独有保留。
  - 分组：先按 id 对齐（备份内容替换），再把备份中与某本地分组**同名但不同 id**的分组合并进
    该本地分组（成员 id 取并集，保留本地 id），本地独有分组保留。绝不删除本地独有内容。
  - `changedCount` = 实际被替换或新增的（服务商 + 分组 + 条目）数量。
- `mergeBackupProviderConfig` 改用该函数，返回值改为实际改动数；
  `BackupImporter` 的 `report.imported` 直接用该计数（不再用 after-before）。
- API Key：同 id 服务商的凭据以备份 secrets.json 为准覆盖写入（现有 secrets 导入路径核对后接线）。

## 5. 跨 API 类型切换不再因工具 ID 失败

**根因**：`sanitizeToolId` 只在发往 Anthropic 时套用，历史里存的是原始 id。OpenAI Responses
的 `call_abc|fc_def` 写进历史后，切到 Anthropic 时 tool_use 被清洗、配对的 tool_result 若走
另一路径仍带原 id，两端不一致导致 400。

**方案**：
- `sanitizeToolId` 保持现有规则（字母数字、`_`、`-`，其余变 `-`），这是各 API 的交集。
- 在**写入历史**的位置（agent loop 记录 tool_use / tool_result 处）统一清洗，
  使两端永远用同一个 id。Anthropic 出口的清洗保留作为防御层（幂等）。
- 新增对历史消息的归一化：发送前对同一会话内成对的 tool_use 与 tool_result 用同一函数清洗，
  保证旧历史也能过。

## 6. 阅读上一条回复时新回复开始不跳动

**根因**：`ChatScreen.kt` 流式跟随（1677 行）在 `isPinned` 时只要
`firstVisibleItemIndex != 0` 就 `scrollToItem(0)`。用户停在最新回复中部阅读时 index 不为 0
但视口仍在最新条目上，新回复一开始视口被拽到底部。

**方案**：
- 新增纯函数 `ScrollAnchor.shouldFollow(isPinned, firstVisibleIndex, isScrolling, withinInterruptWindow)`：
  仅当 pinned、且 `firstVisibleIndex == 0`（视口锚在最新条目上，说明用户没往上翻）、
  且不在主动滚动、且不在用户打断窗口内时才跟随。
- 现有 `tracedScrollToItem` 调用点改用该判定。index 0 但 offset 非 0（停在最新条目中部）不再跳。

## 7. 悬浮胶囊完整可见 + 横竖屏分别记位置

**根因**：`ToolOverlayController.attachTouchListener`（735 行）边界用全屏像素且 y 从 0 起，
顶部被状态栏遮、横屏底部被手势条遮；`setOverlayPosition` 只存一份坐标，横竖屏互相覆盖。

**方案**：
- 新增纯函数 `OverlayBounds.clamp(x, y, capsuleW, capsuleH, screenW, screenH, topInset, bottomInset, sideInset)`
  返回夹取后坐标，保证胶囊整体落在 insets 之内。
- 拖拽与 `onConfigurationChanged` 都走它，insets 取状态栏高度与导航栏/手势条高度
  （`WindowInsets` 的 systemBars）。
- 位置存储改为按 orientation 分键（`overlay_x_port/land`、`overlay_y_port/land`），
  旋转时读写对应键，缺省时用夹取后的默认位置。

## 8. Markdown 预览链接可打开

**根因**：文件预览用 `MarkdownDocument`（StreamingMarkdownText.kt:360），`RenderBlock` 无链接回调，
链接是死的。

**方案**：
- 新增纯函数 `MarkdownLinkResolver.resolve(href, baseDir)`：
  `http/https` 原样返回（网页）；`minis://` 原样；相对路径按 baseDir 解析成绝对文件路径；
  其他 scheme 原样返回交系统处理。拒绝 `..` 穿越到 baseDir 允许根之外（允许根 = 工作区根）。
- `MarkdownDocument` 增加可选 `onLinkClick: ((String) -> Unit)?`，传给 `RenderBlock`。
- `FilePreviewScreen` 传入回调：网页用应用内浏览器打开（现有 browser 路由），
  文件路径用现有文件预览路由打开。

## 验证

每项对应 JUnit4 纯函数测试，放 `src/android/app/src/test`（core 模块逻辑测试放
`src/android/core/model` 的 test 源集，若该模块无 test 源集则上移到 app 测试）。
本地用 python 复述断言做交叉验证；Kotlin 编译与测试交由云端 CI。
