#!/usr/bin/env python3
"""Detect orphan imports in Kotlin files: imported symbol never referenced
in the body (outside the import block itself). Word-boundary match."""
import re, sys

def scan(path):
    src = open(path, encoding='utf-8').read()
    lines = src.split('\n')
    imports = []  # (lineno, full_line, symbol)
    for i, ln in enumerate(lines, 1):
        m = re.match(r'^import\s+([\w.`]+)(?:\s+as\s+(\w+))?\s*$', ln)
        if m:
            fq = m.group(1)
            alias = m.group(2) or fq.split('.')[-1]
            imports.append((i, ln, alias, fq))
    body_start = 0
    for i, ln in enumerate(lines):
        if ln.startswith('import') or ln.startswith('package') or ln.strip() == '' or ln.startswith('//'):
            body_start = i + 1
            continue
        break
    body = '\n'.join(lines[body_start:])
    # strip block comments and string literals crudely for usage counting
    body_nc = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
    body_nc = re.sub(r'//[^\n]*', '', body_nc)
    body_nc = re.sub(r'""".*?"""', '""', body_nc, flags=re.S)
    body_nc = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', body_nc)
    dead = []
    for (i, ln, alias, fq) in imports:
        if fq.endswith('.*'):
            continue  # wildcard: cannot check cheaply
        if not re.search(r'\b' + re.escape(alias) + r'\b', body_nc):
            dead.append((i, ln))
    return dead

for p in sys.argv[1:]:
    for (i, ln) in scan(p):
        print(f"{p}:{i}: {ln}")
