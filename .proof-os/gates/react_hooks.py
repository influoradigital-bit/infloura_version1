#!/usr/bin/env python3
"""gates/react_hooks.py — detects the conditional-hook class forever.

origin: F-0039 (conditional-hook, src/components/3d/PortfolioCanvas.tsx:30)
  `useLoader` was called behind a ternary. Hook-order changes when the prop
  flips => React throws "Rendered fewer hooks than expected" and the subtree
  unmounts. tsc is blind to it (types are fine). gates/frontend.sh was ALSO
  blind: it lints with gates/eslint.sage.mjs, which carries no react-hooks
  plugin at all. So no gate in this repo could see the class.

LAW (from gates/frontend.sh): tool-cannot-run => exit 2 (unavailable, believed).
                              exit 1 ONLY for real findings. exit 0 = proved.
A missing eslint or missing eslint-plugin-react-hooks must NEVER read as green.

usage: react_hooks.py [project_root] [--paths src ...]
"""
import json
import os
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else ".").resolve()
paths = []
if "--paths" in sys.argv:
    paths = sys.argv[sys.argv.index("--paths") + 1:]
if not paths:
    paths = ["src"]
paths = [p for p in paths if (root / p).exists()]
if not paths:
    print("· no target paths present — unavailable")
    sys.exit(2)

eslint = root / "node_modules" / "eslint" / "bin" / "eslint.js"
plugin = root / "node_modules" / "eslint-plugin-react-hooks"
config = Path(__file__).parent / "eslint.hooks.mjs"

if not eslint.exists():
    print("· eslint not installed — unavailable (NOT green)")
    sys.exit(2)
if not plugin.exists():
    print("· eslint-plugin-react-hooks not installed — unavailable (NOT green)")
    sys.exit(2)
if not config.exists():
    print(f"· {config.name} missing — unavailable (NOT green)")
    sys.exit(2)

cmd = ["node", str(eslint), *paths, "--config", str(config),
       "--no-config-lookup", "-f", "json"]
proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True,
                      encoding="utf-8", errors="replace")

raw = (proc.stdout or "").strip()
if not raw:
    # No JSON at all => the tool itself failed. Unavailable, never green.
    print("· eslint produced no output — unavailable (NOT green)")
    print((proc.stderr or "")[:400])
    sys.exit(2)
try:
    data = json.loads(raw)
except json.JSONDecodeError:
    print("· eslint output was not JSON — unavailable (NOT green)")
    print(raw[:400])
    sys.exit(2)

# Only this gate's rule counts. Anything else is another class's ledger record.
hits = []
for f in data:
    for m in f.get("messages", []):
        if m.get("ruleId") == "react-hooks/rules-of-hooks":
            rel = os.path.relpath(f["filePath"], root).replace("\\", "/")
            hits.append(f"{rel}:{m['line']}:{m.get('column', 0)}  {m.get('message', '')[:100]}")

files_linted = len(data)
if hits:
    print(f"VERDICT: broken — {len(hits)} conditional-hook violation(s) across {files_linted} files")
    for h in hits:
        print(f"  {h}")
    sys.exit(1)

print(f"VERDICT: aligned (proved) — 0 rules-of-hooks violations across {files_linted} files")
sys.exit(0)
