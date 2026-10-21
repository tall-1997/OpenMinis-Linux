# 设计：拖动排序 / 压缩动画 / 无 Key 刷新 / 闹钟重注册 / 按钮高度 / 图标统一 / Apple 风格

日期：2026-10-22。客户已安装最新构建，这批是新功能加修复。

## 1. 服务商列表拖动排序

**现状**：ProviderListScreen 用 `instances.groupBy { it.providerType }` 分组显示，
ProviderConfig.instances 是 MutableList，顺序是添加顺序，不可调。
模型选择页 UnifiedModelPickerSheet 按 config.instances 原顺序展示。

**方案**：
- ProviderListScreen 每行左侧增加拖动把手图标
- 拖动手势用 Modifier.draggable 或 pointerInput + detectDragGesturesAfterLongPress
- 排序变更后调用 ProviderRepository.reorderInstances(list) 持久化
- ProviderRepository: 新增 `reorderInstances(orderedIds: List<String>)`，
  在 synchronized 内按给定 id 序列重建 instances 列表
- UnifiedModelPickerSheet 的服务商段落顺序读取 config.instances → 自动跟随

## 2. 压缩上下文动画

**现状**：CompactProgressIndicator 已有进度显示+取消按钮。动画只是 "Compacting…" 文字。

**方案**：
- 用 Apple 设计规范里的 SF Pro 图标风格（circle.dotted / hourglass）
- 添加无限循环的缩放+透明度脉冲动画（对应 Apple 的 breathing 效果）
- 图标：compose 内用 AnimatedVisibility + Scale + Alpha 实现呼吸动画
- 不新增外部依赖，纯 Compose Animations API

## 3. 无 Key 服务商刷新

**现状**：refreshModels 返回 FAILURE 时，catch Exception 分支只打 log。
界面只显示 "Refresh failed"。usableApiKey 已支持无 Key，但错误信息被吞。

**方案**：
- ModelRefreshResult 新增细分状态：NOT_FOUND(404)、CONNECTION_FAILED、INVALID_RESPONSE
- refreshModels 的 catch 块按异常类型细分，
  HTTP 状态码拿得到就判 404 vs 其他；连接异常判 CONNECTION_FAILED；
  解析异常判 INVALID_RESPONSE；其余 FAILURE
- 无 Key 自建服务刷新路径（customBaseURL 非空且 apiKey 为 null 时也尝试调
  fetchModels 再用 "" 作为 key，不再是直接 return）
- ProviderDetailScreen 刷新结果的 toast 按细分状态给不同文案

## 4. 定时任务闹钟重注册

**现状**：AlarmReceiver 已有 BOOT_COMPLETED 分支但只打日志。
更新/强制停止后闹钟丢失，定时任务失效。

**方案**：
- Manifest 新增 action MY_PACKAGE_REPLACED 的 receiver
- 开机/更新广播到达后，遍历 ScheduledTaskStore 所有 enabled 任务，重新 registerAlarm
- ScheduledTaskManager 新增 `rescheduleAll()` 方法

## 5. 全局按钮高度统一

**现状**：MinisButton 已定 48dp。但部分地方直接调 Button/OutlinedButton 无高度限制。

**方案**：
- 全局搜索 Button / OutlinedButton / TextButton 的直接调用（非 Minis 系列）
- 替换为 MinisButton / MinisOutlinedButton / MinisTextButton
- 无法替换的（如 AlertDialog 自带按钮），加 `.defaultMinSize(minHeight = MinisButtonHeight)`

## 6. 设置项行高一致

**现状**：SettingsRow 的 minHeight 参差不齐。

**方案**：
- SettingsRow 默认 minHeight 统一为 56dp（Apple 风格列表行高）
- 已有特殊用途的（如模型详情 72dp）不改

## 7. 聊天"…"菜单图标统一

**现状**：多处直接用 Icons.Default.MoreVert，风格不统一。

**方案**：
- 改用 Icons.Outlined.MoreHoriz（Apple 风格横向三点）
- 所有 MoreVert → MoreHoriz，统一替换

## 8. 屏幕方向跟随系统

**现状**：可能在某处有锁屏方向代码。搜索后无结果 → 可能不存在。确认后跳过。

## 9. 非动态取色时 Apple 风格

**现状**：theme 里已有 ChatColors / MaterialTheme 系统。

**方案**：
- 在非动态取色模式下，覆盖关键颜色 token 为 Apple 设计系统：
  primary → #0066cc, surface → #f5f5f7, background → #ffffff
- 设置项卡片使用 surface-pearl (#fafafc) 背景
- 图标统一用 Rounded 风格（Icons.Rounded.xxx）近似 Apple 的 SF Symbols

## 10. 🔧→搜索图标

**现状**：ChatToolFormatting.kt:55 中 web_search / browser_use 等工具用
Icons.Default.Build（🔧），但卡片展示应显示搜索图标。

**方案**：
- browser_use / web_search / web_fetch 类工具改用 Icons.Default.Search 或 Icons.Default.TravelExplore
- 保留非搜索类工具用 Build 的逻辑