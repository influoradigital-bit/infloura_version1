#!/usr/bin/env python3
"""Build a single self-contained HTML audit dashboard from audit.json.

Usage: python build_dashboard.py audit.json out.html
"""
import json, sys, html, datetime

STATUS = {
    "aligned": ("#166534", "#dcfce7", "Aligned"),
    "partial": ("#92400e", "#fef3c7", "Partial"),
    "broken":  ("#991b1b", "#fee2e2", "Broken"),
    "missing": ("#991b1b", "#fee2e2", "Missing"),
}
ORDER = {"missing": 0, "broken": 1, "partial": 2, "aligned": 3}
SCORE = {"aligned": 1.0, "partial": 0.5, "broken": 0.0, "missing": 0.0}


def esc(x):
    return html.escape(str(x))


def main():
    src, out = sys.argv[1], sys.argv[2]
    data = json.load(open(src))
    feats = data.get("features", [])
    for f in feats:
        f.setdefault("score", SCORE.get(f.get("status", "broken"), 0.0))
    feats.sort(key=lambda f: (ORDER.get(f.get("status", "broken"), 9), f.get("name", "")))

    if feats:
        pct = round(sum(f["score"] for f in feats) / len(feats) * 100, 1)
    else:
        pct = 0.0
    pct = data.get("overall_pct", pct)

    counts = {k: sum(1 for f in feats if f.get("status") == k) for k in STATUS}
    proj = esc(data.get("project", "Project"))
    gen = esc(data.get("generated", datetime.date.today().isoformat()))

    rows = []
    for f in feats:
        color, bg, label = STATUS.get(f.get("status", "broken"), STATUS["broken"])
        chain = "".join(
            f'<li class="{ "ok" if c.get("ok") else "no" }">'
            f'<b>{esc(c.get("step",""))}</b> '
            f'<span class="ev">{esc(c.get("evidence",""))}</span></li>'
            for c in f.get("chain", [])
        )
        note = f'<div class="note">{esc(f["note"])}</div>' if f.get("note") else ""
        rows.append(f"""
        <tr>
          <td class="name">{esc(f.get('name',''))}</td>
          <td><span class="badge" style="color:{color};background:{bg}">{label}</span></td>
          <td class="pct">{int(f['score']*100)}%</td>
          <td class="detail"><ul class="chain">{chain}</ul>{note}</td>
        </tr>""")

    summary = " · ".join(
        f'<span style="color:{STATUS[k][0]}">{STATUS[k][2]}: {counts[k]}</span>'
        for k in ("aligned", "partial", "broken", "missing")
    )

    doc = f"""<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{proj} — Audit</title>
<style>
*{{box-sizing:border-box}}
body{{font:15px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
margin:0;background:#f8fafc;color:#0f172a}}
.wrap{{max-width:960px;margin:0 auto;padding:32px 20px}}
h1{{margin:0 0 4px;font-size:24px}}
.meta{{color:#64748b;font-size:13px;margin-bottom:24px}}
.hero{{background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:24px;margin-bottom:20px}}
.big{{font-size:44px;font-weight:700;line-height:1}}
.bar{{height:12px;background:#e2e8f0;border-radius:99px;overflow:hidden;margin:14px 0 10px}}
.bar>i{{display:block;height:100%;width:{pct}%;
background:linear-gradient(90deg,#ef4444,#f59e0b,#22c55e);border-radius:99px}}
.sum{{font-size:13px}}
table{{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e2e8f0;border-radius:14px;overflow:hidden}}
th,td{{text-align:left;padding:12px 14px;border-bottom:1px solid #f1f5f9;vertical-align:top}}
th{{background:#f1f5f9;font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:#475569}}
.name{{font-weight:600;width:22%}}
.pct{{font-variant-numeric:tabular-nums;font-weight:600;width:8%}}
.badge{{padding:3px 10px;border-radius:99px;font-size:12px;font-weight:600;white-space:nowrap}}
.chain{{margin:0;padding-left:18px;font-size:13px}}
.chain li.ok{{color:#166534}} .chain li.no{{color:#991b1b}}
.ev{{color:#64748b;font-family:ui-monospace,Menlo,monospace;font-size:12px}}
.note{{margin-top:6px;font-size:13px;color:#991b1b}}
</style></head><body><div class="wrap">
<h1>{proj} — Model & Feature Audit</h1>
<div class="meta">Generated {gen} · source code is the only source of truth</div>
<div class="hero">
  <div class="big">{pct}%</div>
  <div class="bar"><i></i></div>
  <div class="sum">{summary}</div>
</div>
<table>
<thead><tr><th>Feature / Model</th><th>Status</th><th>Score</th><th>Verification chain</th></tr></thead>
<tbody>{''.join(rows)}</tbody>
</table>
</div></body></html>"""

    open(out, "w").write(doc)
    print(f"Wrote {out} — {pct}% overall, {len(feats)} features")


if __name__ == "__main__":
    main()
