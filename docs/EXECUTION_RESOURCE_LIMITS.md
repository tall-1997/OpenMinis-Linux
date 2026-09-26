# 工具限额：执行资源

入口：多智能体 → 工具限额。复用 ToolLimitPrefs，无数据库迁移。不依赖多智能体启用开关。

- 自动调整：默认开启。只限制尚未启动的重工具调用；按可用内存降低准入，恢复时每 10 秒逐级增加。不暂停运行中任务，不对单任务设内存硬上限。
- 重任务上限：1–4，默认 1。移除页面中的普通命令总并发设置，轻工具不经过重任务队列。包管理器保持独占锁。
- 排队超时：0–1800 秒，新配置默认 0（持续等待，直至资源恢复或用户取消）。已有非零配置保留，仍会按用户设置超时。执行计时在获准后开始；取消等待不执行；降低并发不取消已运行任务。
- 高级设置：空闲 Shell 保留 0–8（默认 2），空闲回收 1–30 分钟（默认 3）。每 30 秒检查一次。
- 空闲回收是保守的缓存目标，不是进程硬上限。检测到任何同 UID 非 Shell 子进程、Shell 有子进程或 procfs 信息不完整时，不回收。可能暂时超过目标。回收会清除 Shell 局部变量和工作目录。

## 验证

compileDebugKotlin 通过。新增 AdaptiveExecutionGateTest 覆盖缩扩容、取消、超时、重任务总预算、压力队首跳过。现有 HeavyTaskAdmissionTest 和 SandboxResourceGateTest 通过。

全量 app:testDebugUnitTest：1698 项，1696 通过，2 项失败：DatabaseVersionGuardTest.guard constant matches the latest exported schema version、SecurityGateImplTest.fatalBansRmRfRoot。未修改这两处涉及的数据库/安全策略，未在基线重跑确认归因。

未安装 APK，未进行 OPPO 重启复现或内存改善量测。

## 已知限制

非零排队设置覆盖包管理锁和重任务准入的共同等待预算，不含同 lane Mutex 等待。后台任务不受前台命令并发计数约束，受保守回收保护。轻量清理命令可绕过重任务队列，但仍受同 lane 顺序约束。自动预算当前仅接入 ExecutionCoordinator；其他直接调用 SandboxResourceGate 的路径使用固定配置上限。

本轮增量编译和 AdaptiveExecutionGateTest、HeavyTaskAdmissionTest、SandboxResourceGateTest、SubAgentLaneTest 全部通过。新增长于旧超时的无限等待与轻工具独立执行测试。SubAgentRunner 工具调用直接挂起等待，并透传 CancellationException，没有在该调用处加墙钟超时。尚未完成真实多 JVM 设备端压测，也没有实现崩溃后的持久队列恢复。WAITING_RESOURCE/RUNNING 当前通过工具预览呈现，不是独立 UI 状态枚举。

本次不改变消息内容/分页预算，不截断用户历史。
