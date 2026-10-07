import sys, os, tempfile
sys.path.insert(0, 'scripts')
from safe_patch import patch, insert_after, PatchError

# normal path
f = tempfile.mktemp(suffix='.kt')
open(f, 'w').write('val a = 1\nval b = 2\n')
patch(f, 'val a = 1', 'val a = 10')
assert 'val a = 10' in open(f).read()

# idempotent insert: second call must be a no-op
insert_after(f, 'val a = 10', '// marker')
insert_after(f, 'val a = 10', '// marker')
content = open(f).read()
assert content.count('// marker') == 1, 'idempotency broken'

# error path: pattern not found
try:
    patch(f, 'val zzz', 'x')
    raise SystemExit('FAIL: should have raised')
except PatchError as e:
    assert 'not found' in str(e), e

# error path: ambiguous pattern
open(f, 'w').write('dup\ndup\n')
try:
    patch(f, 'dup', 'x')
    raise SystemExit('FAIL: should have raised')
except PatchError as e:
    assert 'not unique' in str(e), e

os.unlink(f)
print('safe_patch self-test: ALL PASS')
