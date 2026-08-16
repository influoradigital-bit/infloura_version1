#!/usr/bin/env python3
"""
proof-os gate for the `dto-drift` class
(F-0214, F-0215, F-0216, F-0219, F-0220, F-0221).

Thin wrapper. The real check lives at ci/dto-drift-check.py, inside the project, so the same
command runs in CI and from a developer shell — a gate that only exists under .proof-os/ can
pass here while CI never runs it, which is how a class gets "closed" without being defended.

Exit: 0 no drift · 1 drift found · 2 the check could not run (never green by default)
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

# .proof-os/gates/dto-drift.py -> project root
ROOT = Path(__file__).resolve().parent.parent.parent
CHECK = ROOT / "ci" / "dto-drift-check.py"


def main() -> int:
    if not CHECK.exists():
        print(f"UNAVAILABLE: {CHECK} is missing — the gate cannot prove anything", file=sys.stderr)
        return 2
    proc = subprocess.run([sys.executable, str(CHECK), *sys.argv[1:]], cwd=str(ROOT))
    # 0/1 are real verdicts from the check; anything else is the check itself failing to run.
    return proc.returncode if proc.returncode in (0, 1) else 2


if __name__ == "__main__":
    sys.exit(main())
