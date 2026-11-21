#!/usr/bin/env python3
"""CI 守卫：加密 prefs 文件不许静默降级成明文。

为什么需要单独一条：
  `Prefs.file(name)` 按 `PrefsRegistry` 里该文件的 `PrefsScope` 决定走
  `EncryptedSharedPreferences` 还是普通 `getSharedPreferences`。加密性不写在
  调用点，只写在注册表里 —— 于是「迁移一个文件到 Prefs.file」这个动作本身就会
  把加密线索抹掉：

    迁移前  EncryptedPrefsFactory.safeCreate(ctx, "minis_network_secrets")
            → 生成器扫到 safeCreate 字面量，登记 ENCRYPTED
    迁移后  Prefs.file("minis_network_secrets")
            → 生成器只看到普通文件，登记 PLAIN

  注册表照样与源码「一致」，`check_prefs_registry.py` 通过，编译通过，运行通过。
  唯一的后果是代理密码、rclone 令牌开始以明文写进
  `/data/data/<pkg>/shared_prefs/*.xml`，而没有任何一处会报错。

  实测发生过：`minis_network_secrets` 与 `backup_rclone_secrets` 在同一轮迁移里
  被降级成 PLAIN，是人工比对生成器输出的「加密 7 → 9」才发现。

判据两条：
  1. 文件名/键名含 secret|token|password|credential|apikey|private 的文件，
     注册表里必须是 ENCRYPTED。这条是启发式，命中即失败，要求人工在
     `scripts/prefs/prefs_extra_keys.json` 里显式声明 `encrypted` 或改名。
  2. 历史上被登记过 ENCRYPTED 的文件不许变成 PLAIN —— 以
     `scripts/ci/prefs-encrypted-baseline.json` 为钉住的名单，只增不减。

用法：
    python3 scripts/ci/check_prefs_encryption.py [--update-baseline]
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "src/android/app/src/main/java/com/openminis/app/config/PrefsRegistry.kt"
BASELINE = ROOT / "scripts/ci/prefs-encrypted-baseline.json"

# 名字里带这些词的文件按「必须加密」处理
SECRETISH = re.compile(r'secret|token|password|credential|apikey|api_key|private', re.I)

RE_SPEC = re.compile(
    r'PrefsFileSpec\(\s*name\s*=\s*"([^"]+)"[^)]*?scope\s*=\s*PrefsScope\.(\w+)',
    re.S,
)


def parse_registry(src: str):
    return {m.group(1): m.group(2) for m in RE_SPEC.finditer(src)}


def main() -> int:
    update = '--update-baseline' in sys.argv
    if not REGISTRY.exists():
        print(f'prefs encryption: SKIP ({REGISTRY} not found)')
        return 0
    files = parse_registry(REGISTRY.read_text(encoding='utf-8'))
    if not files:
        print('prefs encryption: FAILED —— 注册表里一个 PrefsFileSpec 都没解析到，正则已失效')
        return 1

    encrypted = {n for n, s in files.items() if s == 'ENCRYPTED'}
    baseline = {'encrypted': []}
    if BASELINE.exists():
        baseline = json.loads(BASELINE.read_text(encoding='utf-8'))
    pinned = set(baseline.get('encrypted') or [])

    fails = []

    # 判据 1：名字像密钥的文件必须加密
    for name, scope in sorted(files.items()):
        if SECRETISH.search(name) and scope != 'ENCRYPTED':
            fails.append(
                f'{name}: 文件名像密钥库，注册表里却是 {scope} —— '
                f'在 scripts/prefs/prefs_extra_keys.json 里给它加 "encrypted": true'
            )

    # 判据 2：钉住的名单只增不减
    for name in sorted(pinned - encrypted):
        fails.append(
            f'{name}: 曾被登记为 ENCRYPTED，现在是 PLAIN —— '
            f'迁移到 Prefs.file 时必须在该文件的清单条目里保留 "encrypted": true，'
            f'否则密钥改为明文落盘且无任何报错'
        )

    if update:
        merged = sorted(encrypted | pinned)
        BASELINE.write_text(
            json.dumps({'note': '曾登记为 ENCRYPTED 的 prefs 文件。只增不减。',
                        'encrypted': merged}, ensure_ascii=False, indent=2) + '\n',
            encoding='utf-8')
        print(f'baseline updated: {len(merged)} 个加密文件')
        return 0

    if fails:
        print(f'prefs encryption: FAILED ({len(fails)} 项)')
        for f in fails:
            print('  · ' + f)
        return 1

    print(f'prefs encryption: OK（{len(encrypted)} 个加密文件，{len(pinned)} 个钉住）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
