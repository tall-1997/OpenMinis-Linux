#!/usr/bin/env python3
"""PRoot-safe patching helpers.

Why this exists: the PRoot filesystem occasionally *silently rolls back*
writes — open(p,'w').write(s) returns without error, but the file on disk
is unchanged. It bit this repo twice (a RadioButton import and a round-4
patch batch both reported OK and never landed). Every helper here writes,
immediately reads the file back, and retries until the change is actually
on disk — or fails loudly.

Usage from a patch script:

    import sys; sys.path.insert(0, 'scripts')
    from safe_patch import patch, insert_after

    patch('src/.../File.kt', 'old text', 'new text')
    insert_after('src/.../File.kt', 'anchor line', 'inserted line')

All functions verify on-disk state after writing and raise PatchError
after MAX_RETRIES failed attempts. Never use open().write() directly for
repo edits.
"""
from __future__ import annotations

import os
import sys
import time

MAX_RETRIES = 3
RETRY_DELAY_S = 0.5


class PatchError(RuntimeError):
    """Raised when a patch cannot be confirmed on disk after retries."""


def _read(path: str) -> str:
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def _write_verified(path: str, expected: str, absent: str | None = None) -> None:
    """Write `expected` to `path` and verify it landed. Retries on rollback.

    `absent`, when given, must NOT be present after the write (used to
    confirm a replacement actually replaced something).
    """
    for attempt in range(1, MAX_RETRIES + 1):
        with open(path, 'w', encoding='utf-8') as f:
            f.write(expected)
            f.flush()
            os.fsync(f.fileno())
        # Re-open: the read must go through the filesystem, not any cache.
        time.sleep(0.05)
        actual = _read(path)
        if actual == expected and (absent is None or absent not in actual):
            return
        print(f'  [safe_patch] write #{attempt} to {path} rolled back; retrying',
              file=sys.stderr)
        time.sleep(RETRY_DELAY_S)
    raise PatchError(f'write to {path} never landed after {MAX_RETRIES} attempts')


def patch(path: str, old: str, new: str, count: int = 1) -> None:
    """Replace `old` with `new` (first `count` occurrences) and verify on disk."""
    s = _read(path)
    if old not in s:
        raise PatchError(f'pattern not found in {path}:\n{old[:120]}')
    if count == 1 and s.count(old) > 1:
        raise PatchError(f'pattern is not unique in {path} ({s.count(old)} matches); '
                         f'narrow it or pass count explicitly')
    updated = s.replace(old, new, count)
    _write_verified(path, updated, absent=(old if new != old and old not in new else None))
    print(f'  [safe_patch] {path}: {count} replacement(s) verified on disk')


def insert_after(path: str, anchor: str, text: str) -> None:
    """Insert `text` right after the first `anchor` line; verify on disk."""
    s = _read(path)
    if anchor not in s:
        raise PatchError(f'anchor not found in {path}:\n{anchor[:120]}')
    if text in s:
        return  # already applied — idempotent
    idx = s.index(anchor) + len(anchor)
    updated = s[:idx] + '\n' + text + s[idx:]
    _write_verified(path, updated)
    print(f'  [safe_patch] {path}: insert verified on disk')


def prepend_import(path: str, import_line: str, after: str = 'import ') -> None:
    """Add an import line in sorted position near existing imports; verify."""
    s = _read(path)
    if import_line in s:
        return  # already applied
    lines = s.split('\n')
    last_import = -1
    for i, ln in enumerate(lines):
        if ln.startswith(after):
            last_import = i
    if last_import < 0:
        raise PatchError(f'no imports found in {path}')
    lines.insert(last_import + 1, import_line)
    _write_verified(path, '\n'.join(lines))
    print(f'  [safe_patch] {path}: import {import_line.rsplit(".", 1)[-1]} verified')


if __name__ == '__main__':
    print(__doc__)
