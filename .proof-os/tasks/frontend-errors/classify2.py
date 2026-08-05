import re, os
txt = open('.proof-os/tasks/frontend-errors/eslint.after-venv-fix.txt', encoding='utf-8', errors='replace').read()
lines = txt.splitlines()
cur = None
rows = []  # (file, line, col, sev, msg)
for ln in lines:
    s = ln.strip()
    if ln and not ln.startswith(' ') and not s.startswith('✖') and re.search(r'\.(tsx|ts|jsx|js|mjs|cjs)$', s):
        cur = s
    else:
        m = re.match(r'^\s+(\d+):(\d+)\s+(error|warning)\s+(.*?)\s*$', ln)
        if m and cur:
            rows.append([cur, int(m.group(1)), int(m.group(2)), m.group(3), m.group(4)])

def area(p):
    q = p.replace('\\', '/')
    if '/.venv/' in q or '/node_modules/' in q: return 'VENDORED'
    if '/components/brand/' in q or '/pages/brand' in q or '/hooks/brand/' in q: return 'BRAND'
    if '/components/creator/' in q or '/pages/creator' in q or '/hooks/creator/' in q: return 'CREATOR'
    if '/admin/' in q: return 'ADMIN'
    return 'SHARED'

def klass(msg):
    if 'setState synchronously within an effect' in msg: return 'set-state-in-effect (cascading renders)'
    if 'Cannot create components during render' in msg: return 'component-created-in-render'
    if 'access refs during render' in msg: return 'ref-access-in-render'
    if 'was not found' in msg: return 'CONFIG: rule not defined (lint config bug, not code)'
    if 'no-explicit-any' in msg or 'Unexpected any' in msg: return 'no-explicit-any'
    if 'no-unused-vars' in msg or ('is defined but never used' in msg) or ('is assigned a value but never used' in msg): return 'no-unused-vars'
    if 'exhaustive-deps' in msg or 'React Hook' in msg: return 'exhaustive-deps'
    if 'only-export-components' in msg or 'Fast refresh' in msg: return 'react-refresh/only-export-components'
    if 'no-console' in msg or 'Unexpected console' in msg: return 'no-console'
    if 'no-restricted-properties' in msg: return 'no-restricted-properties'
    if 'eslint-disable' in msg: return 'unused-eslint-disable'
    return 'other: ' + msg[:50]

root = os.getcwd().replace('\\', '/') + '/'
from collections import defaultdict
by_area = defaultdict(lambda: {'e':0,'w':0,'files':defaultdict(lambda:[0,0,defaultdict(int)])})
by_area_class = defaultdict(lambda: defaultdict(lambda:[0,0]))
for f,l,c,sev,msg in rows:
    a=area(f); k=klass(msg)
    d=by_area[a]; d['e']+= sev=='error'; d['w']+= sev=='warning'
    rel=f.replace('\\','/').replace(root,'')
    fd=d['files'][rel]; fd[0]+= sev=='error'; fd[1]+= sev=='warning'; fd[2][k]+=1
    ca=by_area_class[a][k]; ca[0]+= sev=='error'; ca[1]+= sev=='warning'

order=['BRAND','CREATOR','ADMIN','SHARED','VENDORED']
lines_out=[]
for a in order:
    d=by_area[a]
    lines_out.append(f"\n{'#'*72}\n# {a}  —  {d['e']} errors, {d['w']} warnings, {len(d['files'])} files\n{'#'*72}")
    lines_out.append("  by rule:")
    for k,(e,w) in sorted(by_area_class[a].items(), key=lambda x:-(x[1][0]+x[1][1])):
        lines_out.append(f"    {e:3d}e {w:3d}w  {k}")
    lines_out.append("  by file:")
    for rel,(e,w,ks) in sorted(d['files'].items(), key=lambda x:(-x[1][0],-x[1][1])):
        kk=', '.join(f"{kn}×{cn}" for kn,cn in sorted(ks.items(),key=lambda x:-x[1]))
        lines_out.append(f"    [{e}e/{w}w] {rel}")
        lines_out.append(f"          {kk}")
rep='\n'.join(lines_out)
print(rep)
open('.proof-os/tasks/frontend-errors/classified.txt','w',encoding='utf-8').write(rep)
PY_TOTAL = sum(1 for r in rows if r[3]=='error')
print(f"\nTOTAL rows: {len(rows)} | errors {sum(1 for r in rows if r[3]=='error')} | warnings {sum(1 for r in rows if r[3]=='warning')}")
