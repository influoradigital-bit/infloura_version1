import re, os
txt = open('.proof-os/tasks/frontend-errors/eslint.txt', encoding='utf-8', errors='replace').read()
lines = txt.splitlines()
cur = None
files = {}
for ln in lines:
    stripped = ln.strip()
    is_header = (ln and not ln.startswith(' ') and not stripped.startswith('✖')
                 and re.search(r'\.(tsx|ts|jsx|js|mjs|cjs)$', stripped))
    if is_header:
        cur = stripped
        files.setdefault(cur, {'error': 0, 'warning': 0, 'rules': {}, 'msgs': []})
    elif cur and re.search(r'^\s+\d+:\d+\s+(error|warning)\s+', ln):
        sev = 'error' if re.search(r'\s+error\s+', ln) else 'warning'
        files[cur][sev] += 1
        rule = ln.rstrip().split()[-1]
        files[cur]['rules'][rule] = files[cur]['rules'].get(rule, 0) + 1
        files[cur]['msgs'].append(ln.strip())

def area(p):
    q = p.replace('\\', '/')
    if '/components/brand/' in q or '/pages/brand' in q or '/brand/' in q: return 'BRAND'
    if '/components/creator/' in q or '/pages/creator' in q or '/creator/' in q: return 'CREATOR'
    if '/admin/' in q: return 'ADMIN'
    return 'OTHER'

buckets = {'BRAND': [], 'CREATOR': [], 'ADMIN': [], 'OTHER': []}
for f, d in files.items():
    if d['error'] + d['warning'] == 0: continue
    buckets[area(f)].append((f, d))

root = os.getcwd().replace('\\', '/') + '/'
out = []
grand_e = grand_w = 0
for a in ['BRAND', 'CREATOR', 'ADMIN', 'OTHER']:
    b = buckets[a]
    te = sum(d['error'] for _, d in b)
    tw = sum(d['warning'] for _, d in b)
    grand_e += te; grand_w += tw
    out.append(f"\n{'='*70}\n{a}: {len(b)} files, {te} errors, {tw} warnings\n{'='*70}")
    for f, d in sorted(b, key=lambda x: (-x[1]['error'], -x[1]['warning'])):
        rel = f.replace('\\', '/').replace(root, '')
        rules = ', '.join(f"{k}×{v}" for k, v in sorted(d['rules'].items(), key=lambda x: -x[1]))
        out.append(f"  [{d['error']}e/{d['warning']}w] {rel}")
        out.append(f"        {rules}")
out.append(f"\nGRAND TOTAL: {grand_e} errors, {grand_w} warnings across {sum(len(v) for v in buckets.values())} files")
report = '\n'.join(out)
print(report)
open('.proof-os/tasks/frontend-errors/by-area.txt', 'w', encoding='utf-8').write(report)

# rule frequency across all
allrules = {}
for f, d in files.items():
    for r, c in d['rules'].items():
        allrules[r] = allrules.get(r, 0) + c
print("\n\nRULE FREQUENCY (all areas):")
for r, c in sorted(allrules.items(), key=lambda x: -x[1]):
    print(f"  {c:4d}  {r}")
