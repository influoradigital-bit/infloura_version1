#!/usr/bin/env python3
"""
proof-os gate for the `stale-comment` class (F-0075, F-0150; partial for F-0095).

Thin wrapper — the real check is ci/stale-comment-check.py, inside the project, so the same
command runs in CI (frontend-checks) and from a developer shell.

Covers, each negative-control checked against the defect it was filed for:
  F-0075  a client method that both calls the backend and rejects as unimplemented
  F-0150  prompt/tool-schema content changed without a PROMPT_VERSION bump
  F-0095  PARTIAL — dead `file.ext:NN` citations only. F-0095's actual form was a comment that
          cited LIVE code and described it wrongly, which no rule here detects. Do not read a
          green run as "the comments are accurate".

Usage: python .proof-os/gates/stale-comment.py [--since <ref>]
Exit:  0 clean · 1 a stale claim found · 2 the check could not run
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
CHECK = ROOT / "ci" / "stale-comment-check.py"


def main() -> int:
    if not CHECK.exists():
        print(f"UNAVAILABLE: {CHECK} is missing — the gate cannot prove anything", file=sys.stderr)
        return 2
    args = sys.argv[1:] or ["--since", "HEAD~1"]
    proc = subprocess.run([sys.executable, str(CHECK), *args], cwd=str(ROOT))
    return proc.returncode if proc.returncode in (0, 1) else 2


if __name__ == "__main__":
    sys.exit(main())
