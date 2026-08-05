#!/usr/bin/env python3
"""gates/react_hooks_immutability.py — detects the use-before-declare class.

origin: F-0040 (use-before-declare, src/hooks/useEscrowFund.ts:325,377)
  Two self-rescheduling useCallbacks referenced their own const before it was
  initialized (recursion by name). eslint react-hooks/immutability:
  "Cannot access variable before it is declared". tsc is blind (runtime works
  via closure). gates/react_hooks.py enforces rules-of-hooks ONLY, so it did
  not see this sub-class. gates/frontend.sh lints with eslint.sage.mjs, which
  carries no react-hooks plugin at all. No gate covered this class.

SCOPE: the "accessed before it is declared" immutability sub-class only, over
  src/hooks by default. Deliberately narrow: enabling the full immutability
  rule tree-wide would be permanently red (DiscoverCanvas/HeroGlobe "value
  cannot be modified", creator-chat.tsx:1035 is a SIBLING before-declare
  instance tracked in its own ledger record) — a permanently-red gate is
  F-0015's false-red class. When the sibling instances close, widen --paths.

LAW (gates/frontend.sh): tool-cannot-run => exit 2 (unavailable, believed).
                         exit 1 ONLY for real findings. exit 0 = proved.
Missing eslint / plugin / config must NEVER read as green.

usage: react_hooks_immutability.py [project_root] [--paths src/hooks ...]
"""
import json
import os
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else ".").resolve()
paths = sys.argv[sys.argv.index("--paths") + 1:] if "--paths" in sys.argv else ["src/hooks", "src/pages"]
paths = [p for p in paths if (root / p).exists()]
if not paths:
    print("· no target paths present — unavailable")
    sys.exit(2)

eslint = root / "node_modules" / "eslint" / "bin" / "eslint.js"
plugin = root / "node_modules" / "eslint-plugin-react-hooks"
config = Path(__file__).parent / "eslint.hooks.immutability.mjs"
for missing, label in ((eslint, "eslint"), (plugin, "eslint-plugin-react-hooks"), (config, config.name)):
    if not missing.exists():
        print(f"· {label} missing — unavailable (NOT green)")
        sys.exit(2)

cmd = ["node", str(eslint), *paths, "--config", str(config), "--no-config-lookup", "-f", "json"]
proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True, encoding="utf-8", errors="replace")
raw = (proc.stdout or "").strip()
if not raw:
    print("· eslint produced no output — unavailable (NOT green)")
    print((proc.stderr or "")[:400])
    sys.exit(2)
try:
    data = json.loads(raw)
except json.JSONDecodeError:
    print("· eslint output was not JSON — unavailable (NOT green)")
    print(raw[:400])
    sys.exit(2)

# Only the "accessed before it is declared" sub-class counts. Other immutability
# errors ("value cannot be modified") are a different class with their own records.
hits = []
for f in data:
    for m in f.get("messages", []):
        if m.get("ruleId") == "react-hooks/immutability" and "before it is declared" in (m.get("message") or ""):
            rel = os.path.relpath(f["filePath"], root).replace("\\", "/")
            hits.append(f"{rel}:{m['line']}:{m.get('column', 0)}  {m['message'].splitlines()[0][:90]}")

files_linted = len(data)
if hits:
    print(f"VERDICT: broken — {len(hits)} use-before-declare violation(s) across {files_linted} files")
    for h in hits:
        print(f"  {h}")
    sys.exit(1)
print(f"VERDICT: aligned (proved) — 0 use-before-declare violations across {files_linted} files")
sys.exit(0)
