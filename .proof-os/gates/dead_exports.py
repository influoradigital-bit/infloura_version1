#!/usr/bin/env python3
"""gates/dead_exports.py — detects genuinely-unreferenced TS exports.

origin: F-0042 (dead-exports, DEAD_CODE.md — 132 ts-prune candidates).
  Raw ts-prune is ~45% false-positive on this repo: it flags type exports
  (src/lib/types.ts: Proposal, Contract, Wallet...) and barrel re-exports
  (components/3d/index.ts, motion/index.ts) that ARE used. Deleting on raw
  ts-prune output would have broken the build. This gate CROSS-VERIFIES every
  ts-prune candidate: an export counts as dead ONLY if its identifier appears
  in ZERO other files across src/ AND scripts/ci/trendspark/public. That is the
  exact check that separated the 73 real deletions from the 59 false positives.

LAW (gates/frontend.sh): tool-cannot-run => exit 2 (unavailable, believed).
                         exit 1 ONLY for real findings. exit 0 = proved.
ts-prune / tsconfig absent => exit 2, never a false green.

usage: dead_exports.py [project_root]
"""
import json
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
if not (root / "tsconfig.json").exists() or not (root / "node_modules").exists():
    print("· no tsconfig.json / node_modules — unavailable (NOT green)")
    sys.exit(2)

# 1. ts-prune candidates
try:
    proc = subprocess.run(["npx", "--yes", "ts-prune", "-p", "tsconfig.json"],
                          cwd=root, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", shell=(sys.platform == "win32"))
except Exception as e:
    print(f"· ts-prune could not run ({e}) — unavailable (NOT green)")
    sys.exit(2)
out = proc.stdout or ""
if not out.strip():
    print("· ts-prune produced no output — unavailable (NOT green)")
    print((proc.stderr or "")[:300])
    sys.exit(2)

def norm(l):
    return l.lstrip("\\").replace("\\", "/")

candidates = []
for l in (x.rstrip() for x in out.splitlines() if x.strip()):
    if "(used in module)" in l:
        continue
    n = norm(l)
    low = n.lower()
    if ".test." in low or ".stories." in low or "vite-env" in low or n.strip().endswith("- default"):
        continue
    m = re.match(r"(.+?):(\d+)\s*-\s*(.+)$", n)
    if m:
        candidates.append((m.group(1), int(m.group(2)), m.group(3).strip()))

# 2. token index across src + non-src code
TOK = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
from collections import defaultdict
occ = defaultdict(set)
scan_files = []
for d in ("src", "scripts", "ci", "trendspark", "public"):
    p = root / d
    if p.exists():
        for ext in ("*.ts", "*.tsx", "*.js", "*.mjs", "*.cjs"):
            scan_files += list(p.rglob(ext))
scan_files += [f for pat in ("*.ts", "*.mjs", "*.js") for f in root.glob(pat)]
for f in scan_files:
    if "node_modules" in str(f):
        continue
    rel = str(f.relative_to(root)).replace("\\", "/")
    try:
        text = f.read_text(encoding="utf-8", errors="replace")
    except Exception:
        continue
    for t in set(TOK.findall(text)):
        occ[t].add(rel)

# 3. an export is dead only if its identifier appears in NO file but its definer
dead = []
for path, line, sym in candidates:
    mi = TOK.match(sym)
    if not mi:
        continue
    ident = mi.group(0)
    others = occ.get(ident, set()) - {path}
    # An export referenced ANYWHERE but its definer — including by a test — is NOT
    # dead: deleting a test-only export breaks that test. This matches the F-0042
    # deletion policy, which kept test-only exports (escrowApi/marketingApi) and
    # deleted only exports with zero references of any kind.
    if not others:  # referenced in no other file at all = genuinely dead
        dead.append(f"{path}:{line}  {sym}")

if dead:
    print(f"VERDICT: broken — {len(dead)} genuinely-unreferenced export(s) (cross-verified vs {len(candidates)} ts-prune candidates)")
    for d in dead[:60]:
        print(f"  {d}")
    sys.exit(1)
print(f"VERDICT: aligned (proved) — 0 genuinely-unreferenced exports ({len(candidates)} ts-prune candidates all cross-verified as still-referenced)")
sys.exit(0)
