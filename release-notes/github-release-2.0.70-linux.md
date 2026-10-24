# OpenMinis-Linux 2.0.70-linux

- versionCode **270**
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`
- 本版说明覆盖自 2.0.60 以来的全部变更（28 个提交）

## New features / 新功能

- **Two visual systems** — Apple (calm, non-dynamic) and M3 Expressive (dynamic color); settings icons follow the toggle in both directions.
  两套独立视觉系统：Apple 风格（非动态取色）与 M3 Expressive（动态取色），设置页图标双向跟随开关。
- **Live plan board** — the AI's task board renders as a live card above the composer; rows carry status only and self-dismiss when the work lands.
  计划板常驻卡片：AI 的任务看板实时显示在输入框上方，行只留状态图标，完成自动收起。
- **Goals are real now** — create from the panel (objective + token budget), a live header card, pause / resume / clear.
  Goal 产品化：面板直接创建（目标 + token 预算），常驻头部卡片，可暂停 / 恢复 / 清除。
- **Free lane restored, plus a second keyless lane** — free models re-seed from a live catalogue with recovery entries; a second free channel joins the same pipeline, and an unknown server error no longer silently retires a free model.
  免费通道恢复：免费模型按活体目录重新播种、带恢复入口；第二条免密钥通道接入同一管线；服务器返回未知错误类型不再让免费模型无声消失。
- **Usage page redo** — brand icons, window-driven filters, brand-color share bars.
  用量页重做：品牌图标、窗口驱动筛选、品牌色占比条。

## Fixes / 修复

- **Dropped streams are resumable** — a cut-off reply keeps its place; Retry resumes instead of regenerating, and the "interrupted" sticker only ever sits on the current tail row.
  被切断的回复可续传：重试改为续写而非重新生成；「已中断」贴纸只挂在当前尾部行，不再残留。
- **Two main-thread crashes in two days, rooted out** — agent history is cross-thread shared state now; plain ArrayList was the crash.
  两天两次的主线程崩溃根治：agent 历史改为跨线程安全容器，根因修非兜底。
- **Search no longer spills message bodies** — raw query expression columns got real aliases.
  搜索不再把原始消息体灌进结果：表达式列补齐别名。
- **The plan board no longer freezes at "step 2 of 4"** — tools the prompt names are always in the core schema.
  看板不再卡在 step 2：提示词点名的工具必进核心 schema。
- **Suite installs heal themselves** — npm fake-death is judged by on-disk payload, half-configured dpkg repairs itself.
  套件安装自愈：npm 假死按磁盘载荷判定修复，dpkg 半配置包自动治愈。
- **Sandbox compiles stop getting OOM-killed** — memory budgets scale with the device instead of one fixed cap.
  沙箱编译不再被内存连坐杀：预算按机型动态。
- **Large-file reads honour direction** — tail returns the real end of the file; disabled env vars now actually stop every consumer.
  大文件读取尊重方向：tail 真读末尾；停用的环境变量真正断开所有消费方。
- **One permissions entry** — the blank self-drawn page is gone; OS grants live on the OS page.
  设置页权限入口归一：空白自绘页删除，直达系统权限页。
- **Soul avatars wired + ten smaller fixes** — model avatars resolve real brand icons; preview JS pop-ups, WebView render crashes, voice dispatch, notification deep-links.
  Soul 模型头像接线与十项小修：预览弹窗、WebView 渲染崩溃、语音派发、通知深链。
- **Models, params, backups** — models are deletable again, parameter priority is stable, backup restore is safer.
  模型可删除、参数优先级稳定、备份恢复更安全。

## Polish / 视觉打磨

- **Group-chat avatars reuse brand icons**; brand resolution has one source of truth.
  群聊头像复用品牌图标，品牌判定单一真相源。
- **Apple Blue theme**, finer refresh states, compress animation, alarms re-register on boot.
  Apple Blue 主题、刷新细分、压缩动画、闹钟重启后重注册。

---

完整提交列表：`a7630b96..862012ef`。
