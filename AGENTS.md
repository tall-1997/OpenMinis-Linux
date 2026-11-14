# AGENTS.md — AI Agent 工作契约

任何 AI agent（Claude Code / Codex / Minis Ultra 等）在本仓库工作时遵守本文件；人类贡献者看 [CONTRIBUTING.md](CONTRIBUTING.md)。本文件随代码走版本控制，是 agent 侧规则的唯一权威来源：会话记忆里与之冲突的旧规则以本文件为准。

## 开工前

长会话、新会话、接手他人分支时：先读本文件，再扫一眼最近的工作记录（git log / 会话记忆），确认此前做到哪一步，防止重复研究或丢失关键结论。

## 提交流程（不可跳步）

分支 → 提交 → push → 开 PR → 等 CI 绿 → squash 合并 → 验证增量非零 → 删除本地与远端分支。

歧义或需要用户确认的决策用提问卡片提出，不靠猜、不擅自扩权。

## 修复纪律

- **先读技能再动手，这一条没有例外**：bug 修复 / 功能开发前，全文读完 `superpowers`（含命中的子技能：systematic-debugging、test-driven-development、verification-before-completion、finishing-a-development-branch）、`ponytail`、`android-skills`（Minis 环境在 `/var/minis/skills/<name>/SKILL.md`）；其他环境的 agent 应用同等纪律：根因优先、测试先行、证据先于结论。UI 改动另读 `awesome-design-md` + `ui-ux-pro-max`；本项目 UI 设计语言为 Apple 风格（动态取色除外）。
- **治本不治标**：找根因，拒绝补丁式修复；最小改动如果留下第二层根因等于没修。改动可能被没碰过的代码作废——修完后沿真实调用链端到端核对（实例：#365 FileChangeHub 修好监听层后，FileEventCoalescer.isIgnored 的 `.git` 丢弃让事件在 offer() 就被吃掉，修复整段失效）。
- 运行中遇到的 bug 顺带修复，不另开任务、不留尾巴。

## 验证纪律

- 本地环境只做 Python 复刻 / 红绿验证；编译与仪器验证全部交云端 CI。
- 边界用例在 Kotlin 测试与 Python 复刻两处钉**同一语义**（深度、层数、上下界），防止两处期望漂移（实例：#365 深度封顶用例期望写反，CI 抓出）。
- 声称「完成 / 修好 / 通过」之前必须拿出证据：CI 绿、红绿复现、diff 增量非零。证据先于结论，没有证据就等于没做。

## 会话纪律

- 默认不用子代理；用户明确要求才用。
- Token 预算不设上限，把思考做足；每次修复兼顾用户体验、开发体验、AI Agent 使用体验，三个都要变好。
- 每轮结束用简体中文大白话总结本轮做了什么。
- 关键结论、线索、根因、验证方法随做随写进会话记忆，防止长会话上下文压缩时丢失。
