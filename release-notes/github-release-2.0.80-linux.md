# OpenMinis-Linux 2.0.80-linux

- versionCode **280**
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`
- 本版说明覆盖自 2.0.70 以来的全部变更（23 个提交）

## New features / 新功能

- **a2a 网关成为内置功能，不再是插件** — Agent2API v3.0.8 由 app 进程托管（`liba2a.so` 直接跑在 Android 上，不落 PRoot），配原生设置页与独立 `:a2a` 面板进程；面板用 app 内 WebView 打开，网关自带的手机版式终于生效。
  a2a is a first-class feature now: host process, APK-native binary, native page, isolated panel process.
- **登录链路从"能用"做到"可靠"** — shim 的 `window.open` 登录窗由 app 内覆盖层承载；只在 shell 里拿到的回调能交回网关；每轮登录用一次性 profile 并按域链过期 cookie，加第二个账户不再继承上一个会话；面板一次点击即打开。
  Sign-in: in-app overlay for the shim's pop-ups, shell-only callbacks delivered, per-round throwaway profiles with domain-scoped expiry.
- **网关日志查看器** — 登录失败能直接看网关日志，不用再去翻 logcat。
  In-app gateway log viewer — login failures explain themselves.
- **本地模型网关独立设置页** — 从插件市场卡片搬进设置，失败诊断去噪。
  Local model gateway gets its own settings page; noisy failure diagnostics trimmed.
- **计划板改成全量替换** — 传整板即替换，行可不带 id（按编号/标题/前缀定位），"标记完成"不再依赖 UUID。
  Plan board takes a full-list replace; rows are targetable without UUIDs.
- **用量页与输入框** — 用量页脚显示 tok/s，队列里粘贴的内容挂成可移除的 chip；重跑选择器能看见消息正文。
  tok/s in the usage footer, queued paste chips at drain, message text in the re-run picker.

## Fixes / 修复

- **a2a 面板闪退根治** — 非主进程不再跑全量 Application 初始化（沙箱抽象套接字、WebView 数据目录天生单实例）。
  Panel crash rooted out: non-main processes boot minimal instead of re-running the whole app init.
- **行内图片不再退化成蓝色链接** — 与正文同行的 `![...](minis://...)` 提升为独立图片块。
  Inline images promote to standalone image blocks instead of degrading into links.
- **会话列表不再出现空白摘要** — reminder-first 的用户轮渲染真实文本，纯 reminder 轮回落到缺省。
  Blank previews gone: reminder-first turns render their real text.
- **网关解压可靠性** — 逐条目关流，破坏性 swap 前逐条目核对 zip 声明的字节数。
  Extraction closes every entry stream and verifies the staged bundle before the swap.
- **入口固定不再自锁** — 虚拟屏幕、边缘跑马灯与 entry-pin self-block 修复。
  Virtual screen, border-traveling marquee, entry-pin self-block fix.
- **CI 原生构建两处硬伤** — 用 `cargo metadata` 解析真实 target-dir（上游 `.cargo/config.toml` 把产物重定向到仓库根），rustc 钉到 1.89.0（上游 lock 里的 aes 0.9.3 拒绝 1.88）。

## Removed / 移除

- **OFM 内置网关全量下线** — 插件市场卡片、设置入口、免费通道、随包 payload 全部删除，内置插件层（目录/安装器/服务管理）一并收摊；本地模型网关与 a2a 承接原来的位置。
  The OFM builtin gateway is gone end to end — market card, settings entry, free lane, bundled payload and the plugin layer itself.

---

完整提交列表：`f25ff168..7e31885c`。
