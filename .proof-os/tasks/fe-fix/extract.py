import re, os, sys
src = sys.argv[1] if len(sys.argv) > 1 else '.proof-os/tasks/frontend-errors/eslint.current.txt'
want = sys.argv[2] if len(sys.argv) > 2 else 'mech'
txt = open(src, encoding='utf-8', errors='replace').read()
root = os.getcwd().replace('\\', '/') + '/'
cur = None

def cls(m):
    if 'never used' in m: return 'unused-vars'
    if 'Unexpected any' in m: return 'explicit-any'
    if 'constant' in m: return 'const-expr'
    if 'setState synchronously within an effect' in m: return 'set-state-in-effect'
    if 'Cannot create components during render' in m: return 'component-in-render'
    if 'access refs during render' in m: return 'ref-in-render'
    if 'impure function during render' in m: return 'impure-in-render'
    if 'cannot be modified' in m: return 'value-modified'
    if 'React Hook' in m or 'dependency' in m: return 'exhaustive-deps'
    if 'only-export' in m or 'Fast refresh' in m: return 'only-export'
    if 'console' in m: return 'no-console'
    if 'eslint-disable' in m: return 'unused-disable'
    if 'memoization' in m: return 'compilation-skipped'
    return 'other'

MECH = {'unused-vars', 'explicit-any', 'const-expr'}
rows = []
for ln in txt.splitlines():
    s = ln.strip()
    if ln and not ln.startswith(' ') and not s.startswith('✖') and re.search(r'\.(tsx|ts|jsx|js)$', s):
        cur = s.replace('\\', '/').replace(root, '')
    else:
        m = re.match(r'^\s+(\d+):(\d+)\s+(error|warning)\s+(.*?)\s*$', ln)
        if m and cur:
            rows.append((cur, int(m.group(1)), int(m.group(2)), m.group(3), cls(m.group(4)), m.group(4)))

if want == 'mech':
    sel = [r for r in rows if r[4] in MECH and r[3] == 'error']
elif want == 'errors':
    sel = [r for r in rows if r[3] == 'error']
elif want == 'all':
    sel = rows
else:
    sel = [r for r in rows if r[4] == want]

from collections import defaultdict
byf = defaultdict(list)
for r in sel:
    byf[r[0]].append(r)
for f in sorted(byf):
    print(f)
    for r in sorted(byf[f], key=lambda x: (x[1], x[2])):
        print(f"    {r[1]}:{r[2]}  [{r[4]}]  {r[5][:75]}")
print(f"\n# {len(sel)} problems in {len(byf)} files")
