# -*- coding: utf-8 -*-
"""Create the PR for fix/upstream-integration -> main and print its number/url."""
import json
import os
import urllib.request

tok = os.environ["GITHUB_TOKEN"]
REPO = "tall-1997/OpenMinis-Linux"
BASE = f"https://api.github.com/repos/{REPO}"

body = """## 合并内容

同步 `origin/main`（`05179c3b` wave5）到我们的上游 issue 修复分支（第 1–5 批 + 四轮对抗审查 + 合并冲突解决 + 架构拆分）。

### 上游 issue 修复（第 1–5 批，45 个工作项）
- **P0**：#520 百炼 maas 语音路由、#539 a11y 权限门、#258/#287 自签 CA、#423/#301 Claude CLI 版本单源
- **稳定性五专项**：工具流 preflight 三入口统一、缓存前缀、shell 超时通道、定时 ANR、TTFB 分阶段
- **规则与设备域**：thinking 规则五修、语音四修、offload 降级、Tailscale 路由清理
- **会话/工具链/UI 22 项** + **采纳批 A 八项**（web_search/extraHeaders/定时模板/boot hook/生命周期 Hook/a11y 清单/子代理预设/自动压缩阈值）

### 与 wave5 的合并
- 18 个冲突文件全部解决：采用 wave5 拆分结构并移植我们的语义修改；strings union；schema 迁移链合并
- 架构拆分 9 个超限文件（逐行移动、行为不变），新文件均 < 800 行
- 经授权按合并后实际行数调高 14 个文件的架构基线；CJK 基线登记 4 处文案债务

### 验证
- `:app:compileReleaseKotlin` BUILD SUCCESSFUL
- 全量单测 **5687 例全部通过**
- 门禁：architecture policy OK、CJK OK、string refs OK、schema drift OK、new-untranslated OK
"""

req = urllib.request.Request(
    f"{BASE}/pulls",
    data=json.dumps({
        "title": "merge: 同步 wave5 (05179c3b) — 上游 issue 修复第 1-5 批 + 四轮对抗审查 + 架构拆分",
        "head": "fix/upstream-integration",
        "base": "main",
        "body": body,
    }).encode(),
    headers={
        "Authorization": f"Bearer {tok}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "minis-agent",
        "Content-Type": "application/json",
    },
    method="POST",
)
resp = json.loads(urllib.request.urlopen(req, timeout=60).read())
print("PR:", resp["number"], resp["html_url"], "state:", resp["state"])
