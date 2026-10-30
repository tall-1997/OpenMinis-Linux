# T-android-a2a-process-crash — 打开 a2a 面板必崩：子进程跑了全量 Application 初始化

## 起因
用户反馈「App 端闪退」。桌面拖出来的闪退日志 10 份，全部是
`Unable to create application com.openminis.app.MinisApp`，进程
`com.openminis.linux:a2a`（2026-10-29 14:45 到 10-30 08:02），时间点与「打开
a2a 面板」一一对应（日志里的 `panel opened (port=18901…)` 就是崩之前最后一行）。

崩溃分两类，根因同一个：

1. **10-29 14:45（2 份，git f9a1f7c1）** —
   `Using WebView from more than one process at once with the same data directory`
   （`AwDataDirLock` / chromium）。`:a2a` 和主进程共用默认 WebView 数据目录，
   后起的那个进程被 chromium 主动 abort。
2. **10-29 19:51 起（8 份，git 18c36b0e…c2472a49）** —
   `NativeOffloadServer.start()` → `LocalServerSocket` 抽象名
   `native-offload-linux` 已被主进程占用 → `bindWithRetry()` 重试耗尽抛异常。

## 根因
`#172` 把 a2a 面板挪进独立进程（`android:process=":a2a"`），但 **Application
仍然按「我是唯一进程」写**：Android 会为每个进程各跑一遍同一个
`Application`，`MinisApp.onCreate` 里的沙箱抽象套接字、PRoot、数据库、
仓库层都是「全 app 一份」的东西，第二个进程再去建就是抢同一个单实例资源。
`:acra` 早就踩过同一个坑（`T287-followup`），当时只针对 ACRA 加了一个
专用早退，没把它一般化——新加的 `:a2a` 于是重新踩了一遍。

第 1 类是同一个根因的另一副面孔：`:a2a` 在 `Application.onCreate` 阶段跑
全量 init，WebView 数据目录的「每进程独占」也属于单实例资源；
`WebView.setDataDirectorySuffix("a2a")` 必须在该进程**任何** WebView 创建
之前调用，而全量 init 让这个前提没法保证。

## 修法（根因，非补丁）
把「我是哪个进程」做成通用判定，任何非主进程只做最小启动：

- **`platform/ProcessChecks.kt`（重写）**：`isMainProcess(context)` /
  `currentProcessName(context)`，三层探测按代价从低到高 ——
  `Application.getProcessName()`（API 28+）→ `/proc/self/cmdline`
  （各 API 都有，内核写的，冷启动不用等 binder）→
  `ActivityManager.getRunningAppProcesses()`（兜底，API 26+ 第三方 app 只看得到
  自己 UID 的条目，不越权）。任一层失败都判成「主进程」，坏掉的探测绝不能让
  主进程少初始化。`parseProcessName()` 抽成纯函数给 JVM 单测钉住。
- **`MinisApp.onCreate`**：ACRA 的 `:acra` 专用早退保留（那是 ACRA 自己的进程），
  紧跟一条通用守卫 `if (!ProcessChecks.isMainProcess(this)) return`。ACRA.init
  仍在 `attachBaseContext`，所以子进程崩了照样有报告；其余（offload 套接字、
  PRoot、DB、仓库、FastMode/Prefs 预热、原生信号处理）都属于主进程。
  `AppLogger.primeContext` 提到守卫之前，子进程也能解析 `filesDir/logs`
  读路径（`enabled` 不置位 → 不写文件，避免两进程写同一份日志）。
- 第 1 类因此一并解决：`:a2a` 不再在 Application 阶段碰任何东西，面板
  `A2aPanelActivity.onCreate` 的 `setDataDirectorySuffix("a2a")` 重新成为该进程
  第一次接触 WebView，前提成立。

## 语义不变项
- `:acra` 上报进程行为逐字不变（它排在通用守卫之前）
- 主进程初始化顺序、内容不变（守卫只是把子进程摘出去）
- a2a 面板的启动/等待逻辑、WebView 隔离、登录窗、无痕 profile 全部不动
- 面板进程里 `A2aGateway`（探活/部署/拉起）照旧可用——它只依赖 Context、
  资源包和 `ProcessBuilder`，不依赖主进程单例

## 已知边界（不在本次修复内，日志里没有对应崩溃）
- API 26/27 没有 `setDataDirectorySuffix`（28 才加），面板进程里创建 WebView
  仍会撞 chromium 的数据目录锁；这两档系统上应用内面板需要单独降级方案。
- `:a2a` 里 `A2aGateway.start()` 会写 `KEY_ENABLED`（SharedPreferences 无跨进程
  失效）。主进程下一次写 prefs 时可能覆盖这次写入，属于持久化精度问题，
  不影响当次运行；面板/网关的进程归属另开 issue 收敛。

## 验收
- `ProcessChecksTest` 6 例钉住 `/proc/self/cmdline` 解析（主进程名、子进程名、
  多 argv、无终止符、空 blob、前导 NUL）
- 本地四道静态门禁全绿（architecture / string refs / CJK / announced tools）
- 编译与单测交 CI
