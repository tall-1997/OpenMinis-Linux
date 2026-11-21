#!/usr/bin/env python3
"""回滚 prefs 迁移中误伤的无关 API 调用。

背景：`getString(` -> `string(` 这类批量替换被应用到了全仓库，把
`context.getString(R.string...)`、`Settings.Secure.getString(...)`、
`JSONObject.getString(...)` 一起改了 —— 这些接收者根本不是 PrefsHandle。

本脚本按「接收者 + 方法」精确匹配，只改回本 PR 改错的那几种形态。
"""
import re
import sys

APP = 'src/android/app/src/main/java/com/openminis/app/'

# (文件, 旧片段, 新片段) —— 逐条精确替换，不做泛化正则，避免二次误伤。
FIXES = [
    # Settings.Secure：Android 框架 API，与 prefs 无关
    ('accessibility/AccessibilityRecoveryManager.kt',
     'Settings.Secure.string(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,',
     'Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,', 2),

    # org.json：JSONObject 的取值方法
    ('mcp/server/approval/McpApprovalStore.kt',
     'taskId = t.string("taskId")', 'taskId = t.getString("taskId")', 1),
    ('mcp/server/approval/McpApprovalStore.kt',
     'operationId = o.string("operationId")', 'operationId = o.getString("operationId")', 1),
    ('mcp/server/approval/McpApprovalStore.kt',
     'o.long("approvedAtMs")', 'o.getLong("approvedAtMs")', 1),
    ('mcp/server/approval/McpApprovalStore.kt',
     'executed.string(i)', 'executed.getString(i)', 1),

    # Context.getString(...)：资源字符串
    ('ui/sessions/SessionListScreen.kt', 'context.string(R.string.', 'context.getString(R.string.', 4),
    ('ui/sessions/SessionListViewModel.kt', 'context.string(R.string.', 'context.getString(R.string.', 1),
    ('ui/settings/backup/BackupViewModel.kt',
     'getApplication<Application>().string(', 'getApplication<Application>().getString(', 1),
]


def apply():
    changed = 0
    for rel, old, new, expect in FIXES:
        p = APP + rel
        src = open(p, encoding='utf-8').read()
        n = src.count(old)
        if n == 0:
            print(f'  SKIP {rel}: 未找到 {old[:40]}')
            continue
        if n != expect:
            print(f'  WARN {rel}: 期望 {expect} 处，实得 {n} 处 —— 仍全部替换')
        src = src.replace(old, new)
        open(p, 'w', encoding='utf-8').write(src)
        changed += n
        print(f'  OK   {rel}: {n} 处  {old[:38]} -> {new[:38]}')
    return changed


if __name__ == '__main__':
    print(f'回滚 {len(FIXES)} 条规则')
    print(f'共改动 {apply()} 处')
