#!/usr/bin/env python3
"""gates/lint_singletons.py — guards the F-0047 singleton correctness fixes.

origin: F-0047 (correctness-lint-singletons). Three residual eslint errors from
  the original deep-check, one per file:
    - no-shadow-restricted-names   CreditMeter.tsx (Infinity icon shadowed global)
    - preserve-caught-error        contract-generator.ts (thrown Error dropped cause)
    - no-misleading-character-class strip-markdown-for-speech.ts (ZWJ/VS16 in class)

Unlike the gate-owned-config gates (react_hooks*), this runs the PROJECT's own
eslint (eslint.config.js) and filters to these three rule IDs, so it enforces
the exact rule definitions the project ships — and it will also catch these
classes anywhere else in src/, not just the three origin files.

LAW: eslint tool cannot run => exit 2 (unavailable). exit 1 for real findings,
exit 0 when clean.
usage: lint_singletons.py [project_root]
"""
import json
import os
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
eslint = root / "node_modules" / "eslint" / "bin" / "eslint.js"
if not eslint.exists() or not (root / "src").exists():
    print("· eslint / src not present — unavailable (NOT green)")
    sys.exit(2)

RULES = {"no-shadow-restricted-names", "preserve-caught-error", "no-misleading-character-class"}

proc = subprocess.run(
    ["node", str(eslint), "src", "-f", "json"],
    cwd=root, capture_output=True, text=True, encoding="utf-8", errors="replace")
raw = (proc.stdout or "").strip()
if not raw:
    print("· eslint produced no output — unavailable (NOT green)")
    print((proc.stderr or "")[:300])
    sys.exit(2)
try:
    data = json.loads(raw)
except json.JSONDecodeError:
    print("· eslint output not JSON — unavailable (NOT green)")
    sys.exit(2)

hits = []
for f in data:
    for m in f.get("messages", []):
        if m.get("ruleId") in RULES:
            rel = os.path.relpath(f["filePath"], root).replace("\\", "/")
            hits.append(f"{rel}:{m['line']}  {m['ruleId']}")

if hits:
    print(f"VERDICT: broken — {len(hits)} singleton correctness violation(s)")
    for h in hits:
        print(f"  {h}")
    sys.exit(1)
print(f"VERDICT: aligned (proved) — 0 violations of {', '.join(sorted(RULES))} across src")
sys.exit(0)
