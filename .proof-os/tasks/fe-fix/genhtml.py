# -*- coding: utf-8 -*-
import re, os, html, json

SRC = '.proof-os/tasks/fe-fix/eslint.final.txt'
OUT = 'FRONTEND-ERRORS.html'

txt = open(SRC, encoding='utf-8', errors='replace').read()
cur = None
rows = []  # dict per problem
for ln in txt.splitlines():
    s = ln.strip()
    if ln and not ln.startswith(' ') and not s.startswith('✖') and re.search(r'\.(tsx|ts|jsx|js)$', s):
        cur = s
    else:
        m = re.match(r'^\s+(\d+):(\d+)\s+(error|warning)\s+(.*?)\s*$', ln)
        if m and cur:
            rows.append({'file': cur, 'line': int(m.group(1)), 'col': int(m.group(2)),
                         'sev': m.group(3), 'msg': m.group(4)})

root = os.getcwd().replace('\\', '/') + '/'
def rel(p): return p.replace('\\', '/').replace(root, '')

def area(p):
    q = p.replace('\\', '/')
    if '/components/brand/' in q or '/pages/brand' in q or '/hooks/brand/' in q: return 'Brand'
    if '/components/creator/' in q or '/pages/creator' in q or '/hooks/creator/' in q: return 'Creator'
    if '/admin/' in q: return 'Admin'
    return 'Shared'

def rule(msg):
    if 'setState synchronously within an effect' in msg: return 'set-state-in-effect'
    if 'Cannot create components during render' in msg: return 'component-in-render'
    if 'access refs during render' in msg: return 'ref-in-render'
    if 'impure function during render' in msg: return 'impure-in-render'
    if 'cannot be modified' in msg: return 'value-modified-in-render'
    if 'Unexpected any' in msg: return 'no-explicit-any'
    if 'never used' in msg: return 'no-unused-vars'
    if 'React Hook' in msg or 'dependency' in msg or 'dependencies' in msg: return 'exhaustive-deps'
    if 'Fast refresh' in msg or 'only-export' in msg: return 'only-export-components'
    if 'console' in msg: return 'no-console'
    if 'constant' in msg: return 'no-constant-binary-expression'
    if 'memoization' in msg: return 'compilation-skipped'
    if 'eslint-disable' in msg: return 'unused-eslint-disable'
    return 'other'

for r in rows:
    r['area'] = area(r['file']); r['rule'] = rule(r['msg']); r['relf'] = rel(r['file'])

AREAS = ['Brand', 'Creator', 'Admin', 'Shared']
tot_e = sum(1 for r in rows if r['sev'] == 'error')
tot_w = sum(1 for r in rows if r['sev'] == 'warning')

# aggregates
from collections import defaultdict, Counter
area_stat = {a: {'e': 0, 'w': 0, 'files': set()} for a in AREAS}
for r in rows:
    a = area_stat[r['area']]; a['e'] += r['sev'] == 'error'; a['w'] += r['sev'] == 'warning'; a['files'].add(r['relf'])
rule_ct = Counter(r['rule'] for r in rows)
rule_e = Counter(r['rule'] for r in rows if r['sev'] == 'error')

def esc(x): return html.escape(str(x))

parts = []
parts.append('<title>Influora — Frontend Error Report</title>')
parts.append('''<style>
:root{--bg:#f7f7f8;--card:#fff;--fg:#1a1a1e;--muted:#6b6b76;--line:#e4e4e9;--err:#c0392b;--errbg:#fdecea;--warn:#9a6a00;--warnbg:#fbf3e0;--ok:#1a7f4b;--okbg:#e8f6ee;--accent:#5b4bff;--code:#f2f2f5;}
@media (prefers-color-scheme:dark){:root{--bg:#0f0f12;--card:#17171c;--fg:#ececf1;--muted:#9a9aa6;--line:#2a2a33;--err:#ff6b5e;--errbg:#2a1614;--warn:#ffca6b;--warnbg:#2a2110;--ok:#5ee39a;--okbg:#12271b;--accent:#8b7dff;--code:#1e1e26;}}
:root[data-theme=dark]{--bg:#0f0f12;--card:#17171c;--fg:#ececf1;--muted:#9a9aa6;--line:#2a2a33;--err:#ff6b5e;--errbg:#2a1614;--warn:#ffca6b;--warnbg:#2a2110;--ok:#5ee39a;--okbg:#12271b;--accent:#8b7dff;--code:#1e1e26;}
:root[data-theme=light]{--bg:#f7f7f8;--card:#fff;--fg:#1a1a1e;--muted:#6b6b76;--line:#e4e4e9;--err:#c0392b;--errbg:#fdecea;--warn:#9a6a00;--warnbg:#fbf3e0;--ok:#1a7f4b;--okbg:#e8f6ee;--accent:#5b4bff;--code:#f2f2f5;}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;padding:32px}
.wrap{max-width:1080px;margin:0 auto}
h1{font-size:24px;margin:0 0 4px}
.sub{color:var(--muted);margin:0 0 24px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:28px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px}
.card .n{font-size:28px;font-weight:700}
.card .l{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}
.badge{display:inline-block;padding:1px 8px;border-radius:99px;font-size:11px;font-weight:600}
.b-err{background:var(--errbg);color:var(--err)} .b-warn{background:var(--warnbg);color:var(--warn)} .b-ok{background:var(--okbg);color:var(--ok)}
.rulebar{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:28px}
.rulebar table{width:100%;border-collapse:collapse}
.rulebar td{padding:4px 0;border-bottom:1px solid var(--line)}
.rulebar td:last-child{text-align:right;color:var(--muted)}
.rname{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px}
details.area{background:var(--card);border:1px solid var(--line);border-radius:12px;margin-bottom:14px;overflow:hidden}
details.area>summary{padding:16px 18px;cursor:pointer;font-size:17px;font-weight:700;list-style:none;display:flex;align-items:center;gap:10px}
details.area>summary::-webkit-details-marker{display:none}
details.area>summary::before{content:"\\25B8";color:var(--muted);font-size:13px}
details.area[open]>summary::before{content:"\\25BE"}
.filegrp{border-top:1px solid var(--line)}
.filehead{padding:10px 18px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12.5px;background:var(--code);display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}
.filehead .p{word-break:break-all}
table.errs{width:100%;border-collapse:collapse}
table.errs td{padding:7px 18px;border-top:1px solid var(--line);vertical-align:top}
td.loc{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;color:var(--muted);white-space:nowrap;width:70px}
td.sev{width:64px}
td.rule{width:200px}
.foot{color:var(--muted);font-size:12px;margin-top:24px;border-top:1px solid var(--line);padding-top:16px}
code{background:var(--code);padding:1px 5px;border-radius:5px;font-size:12px}
.toggle{position:fixed;top:16px;right:16px;background:var(--card);border:1px solid var(--line);color:var(--fg);border-radius:8px;padding:6px 12px;cursor:pointer;font-size:12px}
</style>''')

parts.append('<button class="toggle" id="tg">theme</button>'
             '<script>document.getElementById("tg").onclick=function(){var r=document.documentElement;'
             'r.setAttribute("data-theme", r.getAttribute("data-theme")==="dark"?"light":"dark");};</script>')
parts.append('<div class="wrap">')
parts.append('<h1>Influora — Frontend Error Report</h1>')
parts.append(f'<p class="sub">ESLint + TypeScript oracle sweep · brand · creator · admin · shared &nbsp;·&nbsp; {tot_e} errors, {tot_w} warnings across {len(set(r["relf"] for r in rows))} files</p>')

# summary cards
parts.append('<div class="cards">')
parts.append(f'<div class="card"><div class="n" style="color:var(--ok)">0</div><div class="l">TypeScript errors (tsc --noEmit)</div></div>')
parts.append(f'<div class="card"><div class="n" style="color:var(--err)">{tot_e}</div><div class="l">ESLint errors</div></div>')
parts.append(f'<div class="card"><div class="n" style="color:var(--warn)">{tot_w}</div><div class="l">ESLint warnings</div></div>')
for a in AREAS:
    st = area_stat[a]
    parts.append(f'<div class="card"><div class="n">{st["e"]}<span style="font-size:14px;color:var(--muted)">e / {st["w"]}w</span></div><div class="l">{a} · {len(st["files"])} files</div></div>')
parts.append('</div>')

# rule breakdown
parts.append('<div class="rulebar"><div class="l" style="color:var(--muted);text-transform:uppercase;font-size:12px;letter-spacing:.04em;margin-bottom:8px">Problems by rule</div><table>')
for rl, n in rule_ct.most_common():
    e = rule_e.get(rl, 0); w = n - e
    parts.append(f'<tr><td class="rname">{esc(rl)}</td><td>{"<span class=badge b-err>"+str(e)+" err</span> " if e else ""}{"<span class=badge b-warn>"+str(w)+" warn</span>" if w else ""}</td></tr>')
parts.append('</table></div>')

# per area -> per file -> lines
for a in AREAS:
    st = area_stat[a]
    if st['e'] + st['w'] == 0: continue
    parts.append(f'<details class="area" open><summary>{a} <span class="badge b-err">{st["e"]} err</span> <span class="badge b-warn">{st["w"]} warn</span> <span style="color:var(--muted);font-weight:400;font-size:13px">· {len(st["files"])} files</span></summary>')
    # group by file
    byf = defaultdict(list)
    for r in rows:
        if r['area'] == a: byf[r['relf']].append(r)
    for f in sorted(byf, key=lambda x: (-sum(1 for r in byf[x] if r['sev']=='error'), x)):
        frs = sorted(byf[f], key=lambda r: (r['line'], r['col']))
        fe = sum(1 for r in frs if r['sev'] == 'error'); fw = len(frs) - fe
        parts.append('<div class="filegrp"><div class="filehead"><span class="p">'+esc(f)+'</span><span>'+(f'<span class="badge b-err">{fe}e</span> ' if fe else '')+(f'<span class="badge b-warn">{fw}w</span>' if fw else '')+'</span></div>')
        parts.append('<table class="errs">')
        for r in frs:
            b = 'b-err' if r['sev'] == 'error' else 'b-warn'
            parts.append(f'<tr><td class="loc">{r["line"]}:{r["col"]}</td><td class="sev"><span class="badge {b}">{r["sev"]}</span></td><td class="rule"><span class="rname">{esc(r["rule"])}</span></td><td>{esc(r["msg"])}</td></tr>')
        parts.append('</table></div>')
    parts.append('</details>')

parts.append('''<div class="foot">
<b>How this was produced.</b> Oracles run against the working tree: <code>node_modules/.bin/tsc -p tsconfig.json --noEmit</code> (0 errors) and <code>node_modules/.bin/eslint . -f stylish</code>. Vendored <code>influora-ai/.venv</code> was excluded from linting (config fix). <br><br>
<b>What the oracle cannot see.</b> The <code>set-state-in-effect</code>, <code>*-in-render</code> and <code>exhaustive-deps</code> rules flag <i>potential</i> extra re-renders / stale closures — not proven runtime bugs. No dev server was run, so runtime console/network errors, visual regressions, and whether each screen works with real API data are unverified. TypeScript compiles clean.
</div>''')
parts.append('</div>')

open(OUT, 'w', encoding='utf-8').write('<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' + ''.join(parts[:1]) + ''.join(parts[1:2]) + '</head><body>' + ''.join(parts[2:]) + '</body></html>')
print('wrote', OUT, '·', tot_e, 'errors,', tot_w, 'warnings,', len(rows), 'rows')
