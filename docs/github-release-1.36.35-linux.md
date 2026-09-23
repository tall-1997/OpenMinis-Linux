# 1.36.35-linux 版本更新日志

## 核心修复

### SystemUI 卡死问题修复
- **问题**：MIUI 动态岛（Dynamic Island）高频拉取前台服务通知导致 SystemUI 进程 ANR/OOM 崩溃循环重启
- **根因**：`agent_status` 通知 channel 为 `IMPORTANCE_LOW`（横幅级），被 MIUI 拉入灵动岛 inflate 循环；每次工具/APK 安装更新通知触发 View inflate，3-4 小时后堆碎片化 OOM
- **修复方案**：
  - Channel 降级：`IMPORTANCE_LOW` → `IMPORTANCE_MIN`，设置 `setSound(null, null)` 和 `lockscreenVisibility = VISIBILITY_SECRET`
  - 一次性迁移：检测已存在 channel，如果 importance 高于 MIN 则删除重建
  - Notify 节流：5 秒间隔 + 文本去重，避免高频更新
  - NotificationListenerService 过滤：跳过自家包名的通知，防止被 MIUI 处理
- **影响**：前台服务通知仍正常显示在状态栏，但不再触发 MIUI 灵动岛，从根本上解决 SystemUI 卡死问题

### 翻译功能优化
- **按钮显示逻辑**：AI 折叠模式（隐藏思考和工具过程）下，整个任务完成后只在最后一段显示一个翻译按钮，而非每段都显示
- **布局修复**：移除翻译按钮的 `fillMaxWidth()`，改为自然宽度右对齐，避免遮挡助手输出文字
- **设置简化**：从"设置 → 模型组 → 默认设置"卡片移除翻译槽位（与多智能体配置冲突），翻译模型配置保留在 `ProviderRepository` 中

## UI/UX 改进

### 全局标题居中
- 所有页面的 TopAppBar 改为 `CenterAlignedTopAppBar`，标题水平居中显示
- 涵盖聊天、设置、沙箱、定时任务、会话列表等 30+ 个页面
- 统一视觉风格，符合 Material Design 3 规范

### 主页菜单优化
- 移除会话列表右上角菜单中的"选择"入口（底层选择能力保留，可通过长按会话行触发）
- 简化菜单结构，降低误触风险

## 技术细节

**修改文件**（36 个）：
- 核心服务：`AgentForegroundService.kt`、`MinisNotificationListenerService.kt`
- 翻译功能：`AssistantTranslateButton.kt`、`ChatFlatItems.kt`
- UI 标题居中：30+ 个 Screen 文件
- 设置页面：`ModelGroupsScreen.kt`、`SettingsComponents.kt`、新增 `TranslationSettingsScreen.kt`

**版本信息**：
- versionCode: 87 → 88
- versionName: 1.36.34-linux → 1.36.35-linux

## 注意事项

- SystemUI 修复对已安装用户生效：首次启动前台服务时自动迁移 channel
- 翻译按钮逻辑变化：折叠模式下翻译按钮数量显著减少，点击最后一个按钮可翻译完整回复
- 标题居中为全局变更，所有页面视觉风格统一

---

**参考文档**：
- SystemUI 排查报告：`1-SystemUI卡死重启排查报告.txt`
- Android NotificationChannel 设计：[Android Developers - Notification Channels](https://developer.android.com/develop/ui/views/notifications/channels)
