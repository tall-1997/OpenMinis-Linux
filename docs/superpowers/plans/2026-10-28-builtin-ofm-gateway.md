# Builtin OFM Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把太墟版 Our Free Model 网关作为 OpenMinis 第一个「内置插件」原生集成：APK 内置 payload、通用安装器、本地服务生命周期管理、第三条 BuiltinFreeLane、插件市场管理卡片。

**Architecture:** 通用内置插件层（catalog/installer/store）承载任何自包含运行集；网关服务经 ExecutionCoordinator nohup 后台化，Android 侧 OkHttp 健康检查；Provider 注册走既有 BuiltinFreeLanes 插槽，FreeLaneBootstrap 零改动。

**Tech Stack:** Kotlin + Compose（SettingsSection 既有组件）、OkHttp 4.12、SharedPreferences、shell（TAIXU_* env 契约）、python 镜像验证。

**Spec:** docs/superpowers/specs/2026-10-28-builtin-ofm-gateway.md

## Global Constraints

- 本地只跑 python 验证 + 四道静态门禁；编译交 CI（会话既定约束）。
- CJK 门禁零新增：所有用户可见文案走 strings.xml（en + zh）。
- 架构策略基线只收紧不放宽；新文件行数进 architecture-policy.json。
- 上游 payload（install.sh / launcher / verify.sh / runtime）原样打包，不改一行。
- 不用子代理执行；全部在本会话完成。

## Review Focus

- 端口 18900 被非网关进程占用 → 启动必须失败并给诊断，不能误判「已运行」。
- 网关未运行时 refreshModels → 懒启 30s 上限内未就绪 → fetch 照常执行并自然报连接错误，不悬挂。
- classify 收到连接拒绝 → UNKNOWN，FreeLaneHealth 不落任何 PAID/DEAD 证据。
- 安装中断（进程被杀）→ 状态机恢复为 FAILED（对齐 DevSuiteStore.recoverInterrupted）。
- 用户删除 OFM provider 行 → InstanceRemoval 墓碑 → 重启不重种。

---

## Task 1: LocalGatewayFreeLane + BuiltinFreeLanes.OFM

- [ ] 写 LocalGatewayFreeLaneTest（owns 三分支 / declaredFree / classify 连接拒绝 UNKNOWN / probeHeaders 空）
- [ ] 跑测试确认失败（本地无法跑 gradle——以 python 镜像断言先行，Kotlin 测试随 CI 执行）
- [ ] 实现 LocalGatewayFreeLane.kt（provider/ 包）
- [ ] BuiltinFreeLanes + OFM 条目；FreeLanes.LANE_IDS + "ofm"
- [ ] python 镜像 sim_lane.py 验证 owns/classify 语义
- [ ] 门禁：architecture-policy 基线收录新文件
- [ ] 提交

## Task 2: BuiltinPluginCatalog + Store

- [ ] 写 BuiltinPluginCatalogTest（描述符字段完备、env 契约、目录路径推导纯函数）
- [ ] 实现 BuiltinPluginCatalog.kt / BuiltinPluginStore.kt（plugins/builtin/ 包）
- [ ] python 镜像 sim_install_state.py（状态机迁移 + recoverInterrupted）
- [ ] 门禁 + 提交

## Task 3: BuiltinPluginInstaller + payload 资产

- [ ] zip 打包 payload → assets/builtin_plugins/ofm-runtime.zip（原样，不改上游脚本）
- [ ] 写 BuiltinPluginInstallerTest（解压目标路径、TAIXU_* env 构造、node 门判定纯函数）
- [ ] 实现 BuiltinPluginInstaller.kt（assets 解压 + node 门 + nodesource 22 + install 执行 + probe）
- [ ] python 镜像 sim_installer.py（node 门分支：有 22 / 只有 18 / 无 node）
- [ ] 门禁 + 提交

## Task 4: GatewayServiceManager + 三层拉起入口

- [ ] 写 GatewayServiceManagerTest（start/stop 命令构造、健康检查 URL、状态推导纯函数）
- [ ] 实现 GatewayServiceManager.kt（nohup 启动 / pkill 停止 / OkHttp isRunning / modelCount）
- [ ] ProviderRepository.refreshModels 懒启钩子（单调用点）
- [ ] App 冷启 ensureRunningAsync（enabled 标记）
- [ ] python 镜像 sim_service.py（start 幂等 / stop / 冷启自拉 / 懒启时序）
- [ ] 门禁 + 提交

## Task 5: 插件市场 UI + 文案

- [ ] strings.xml（en + zh）新增 builtin_plugin_* 键
- [ ] BuiltinPluginsSection.kt（SettingsSection + SettingsRow + 确认弹窗 + 启停开关）
- [ ] PluginMarketScreen 接入（DevSuiteSection 之后）
- [ ] check_string_refs / CJK 门禁全绿
- [ ] 提交

## Task 6: PR 流程

- [ ] 分支 builtin-ofm-gateway → 提交 → push（git-data API 幂等脚本）
- [ ] PR 描述（规格链接 + 变更清单）
- [ ] CI 绿 → squash 合并 → 验证增量非零 → 删分支 → local main reset
