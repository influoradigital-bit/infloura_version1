#!/usr/bin/env python3
"""work.py — the /work renderer. Reads OS state (graph+registry+ledger), never opinion.
Usage: work.py            → the map (terminal)
       work.py --html out.html → same map as a shareable page"""
import json, os, sys, html, datetime, signal
try: signal.signal(signal.SIGPIPE, signal.SIG_DFL)
except Exception: pass
import sys as _s; _s.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import data_dir, version
BASE = data_dir()
GP = os.path.join(BASE, "graph.json")
if not os.path.exists(GP):
    print("NOT READY: no graph.json -> run scripts/scan.py --project . first"); sys.exit(3)
G = json.load(open(GP))
RP = os.path.join(BASE, "registry.json")
R = json.load(open(RP)) if os.path.exists(RP) else {"services": {}}
real_services = {k for k in R.get("services", {}) if not k.startswith("_")}
DRAFT = not real_services
LEDP = os.path.join(BASE, "ledger", "failures.jsonl")
LED = [json.loads(l) for l in open(LEDP) if l.strip()] if os.path.exists(LEDP) else []

SYM = {"aligned": "●", "partial": "◐", "broken": "✕", "missing": "○"}
open_f = [f for f in LED if f["status"] == "open"]
counts = G["counts"]

# per-service rollup
svc = {}
for e in G["edges"]:
    if e["src"] in real_services or (DRAFT and e["src"] in {n["id"] for n in G["nodes"] if n["kind"] in ("user-skill","plugin-skill","service")}):
        s = svc.setdefault(e["src"], {"aligned":0,"partial":0,"broken":0,"missing":0})
        s[e["status"]] += 1
def worst(s):
    for k in ("broken","missing","partial","aligned"):
        if s[k]: return k
    return "aligned"

rows = []
for name, s in sorted(svc.items(), key=lambda kv: ({"broken":0,"missing":1,"partial":2,"aligned":3}[worst(kv[1])], kv[0])):
    meta = R.get("services", {}).get(name, {})
    claim = meta.get("may_claim") or "—"   # F-0027: kind 'root' declares may_claim null (PROOFOS.md §4)
    bad = [e for e in G["edges"] if e["src"]==name and e["status"] in ("broken","missing")]
    note = "; ".join(f"{e['status']}: {e['dst'].split('/')[-1]}" for e in bad[:2]) or f"{s['aligned']} aligned edges"
    rows.append((name, meta.get("kind","?"), worst(s), claim, note))

label = 'DRAFT structure-only (registry not approved — run /os-setup)' if DRAFT else 'alignment'
if "--html" not in sys.argv:
    print(f"PROOF-OS {version()} · /work · {G['generated']} · {label} {G['alignment_pct']}% · edges {sum(counts.values())}")
    print(f"{'':1}{'SERVICE':<24}{'KIND':<12}{'ST':<3}{'MAY_CLAIM':<10}NOTE")
    for n,k,w,c,note in rows: print(f" {n:<24}{k:<12}{SYM[w]:<3}{c:<10}{note[:60]}")
    print(f"\nNEEDS YOU ({len(open_f)}):")
    for f in open_f: print(f"  · {f['id']} {f['class']} — {f['fix'][:80]}")
    print(f"\nledger: {len(LED)} records · open {len(open_f)} · run scripts/promote.py --recurrence for the honesty metric")
    sys.exit(0)

out = sys.argv[sys.argv.index("--html")+1]
C = {"aligned":"#22c55e","partial":"#3b82f6","broken":"#ef4444","missing":"#f59e0b"}
trs = "".join(
  f"<tr><td><b>{html.escape(n)}</b></td><td>{k}</td>"
  f"<td><span style='color:{C[w]}'>{SYM[w]} {w}</span></td><td>{c}</td><td>{html.escape(note[:90])}</td></tr>"
  for n,k,w,c,note in rows)
needs = "".join(f"<li><b>{f['id']}</b> {html.escape(f['class'])} — {html.escape(f['fix'])}</li>" for f in open_f)
edge_trs = "".join(
  f"<tr><td>{html.escape(e['src'])}</td><td>{html.escape(e['dst'][:48])}</td><td>{e['type']}</td>"
  f"<td style='color:{C[e['status']]}'>{e['status']}</td><td class='ev'>{html.escape(e['evidence']['where'])} {html.escape(e['evidence'].get('note','')[:70])}</td></tr>"
  for e in G["edges"] if e["status"] in ("broken","missing","partial"))
doc = f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sage OS — /work</title><style>
body{{margin:0;background:#070a0f;color:#e2e8f0;font-family:ui-sans-serif,system-ui;padding:26px}}
h1{{font-size:20px;margin:0}}.sub{{color:#7c8ba1;font-size:12.5px;margin:4px 0 16px}}
.k{{display:inline-block;background:#0d1219;border:1px solid #1e293b;border-radius:8px;padding:8px 14px;margin:0 8px 16px 0;font-size:12px;color:#94a3b8}}
.k b{{color:#e2e8f0;font-size:16px}}
table{{width:100%;border-collapse:collapse;font-size:12.5px;margin-bottom:24px}}
th{{text-align:left;color:#64748b;font-size:10.5px;letter-spacing:1px;padding:0 8px 8px;border-bottom:1px solid #1e293b}}
td{{padding:8px;border-bottom:1px solid #141c27;vertical-align:top}}
td.ev{{color:#7c8ba1;font-size:11.5px}}
h2{{font-size:13px;color:#94a3b8;text-transform:uppercase;letter-spacing:.6px;margin:22px 0 10px}}
.needs{{background:#1a0f0f;border:1px solid #7f1d1d;border-radius:10px;padding:12px 18px}}
.needs li{{margin:6px 0;color:#e8b4b4;font-size:12.5px}}
</style></head><body>
<h1>PROOF-OS · /work</h1><div class="sub">generated {G['generated']} · graph derived from {html.escape(G.get('project') or 'skills')} · nothing hand-drawn</div>
<span class="k">alignment <b>{G['alignment_pct']}%</b></span>
<span class="k">edges <b>{sum(counts.values())}</b></span>
<span class="k">aligned <b style="color:#22c55e">{counts['aligned']}</b></span>
<span class="k">partial <b style="color:#3b82f6">{counts['partial']}</b></span>
<span class="k">broken <b style="color:#ef4444">{counts['broken']}</b></span>
<span class="k">missing <b style="color:#f59e0b">{counts['missing']}</b></span>
<span class="k">ledger <b>{len(LED)}</b> · open <b style="color:#ef4444">{len(open_f)}</b></span>
<h2>Needs you</h2><ul class="needs">{needs or '<li>nothing — silent when green</li>'}</ul>
<h2>Services — worst first</h2>
<table><thead><tr><th>service</th><th>kind</th><th>state</th><th>may_claim</th><th>note</th></tr></thead><tbody>{trs}</tbody></table>
<h2>Non-green edges (evidence attached)</h2>
<table><thead><tr><th>src</th><th>dst</th><th>type</th><th>status</th><th>evidence</th></tr></thead><tbody>{edge_trs}</tbody></table>
</body></html>"""
open(out, "w").write(doc); print(f"wrote {out}")
