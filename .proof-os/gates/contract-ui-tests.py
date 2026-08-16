#!/usr/bin/env python3
"""
proof-os gate for F-0218 — the campaign→contract UI had no test.

F-0218: the deep-link from a collaborator card into /brand/contracts?contract=<id> and the
Generate-contract dialog (open → POST /contracts → success / 403) were verified only by reading
the source. Nothing would have noticed if the wiring were deleted.

This runs the two suites that now cover it. It is deliberately narrow: it names the files whose
absence or failure means F-0218 has regressed, rather than asserting a coverage percentage that
can be satisfied without testing this behaviour at all.

Scope note: this gate closes F-0218 ONLY. The other open records in the
`missing-test-coverage` class are in other languages and other services —
F-0139 (Java: CreatorDiscoveryServiceTest / PortfolioServiceTest) and
F-0146 (Python: influora-ai MONEY_TOOL_SCOPE_DECLINE) — and a frontend vitest run cannot
prove anything about either. They need their own gates.

Usage: python .proof-os/gates/contract-ui-tests.py
Exit:  0 all covered suites pass · 1 a suite failed · 2 a suite is missing or vitest could not run
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

SUITES = [
    "src/pages/brand-campaign-detail.contract-ui.test.tsx",
    "src/pages/brand-campaign-detail.contract-wiring.test.ts",
]


def main() -> int:
    missing = [s for s in SUITES if not (ROOT / s).exists()]
    if missing:
        print(f"UNAVAILABLE: covered suite(s) missing: {', '.join(missing)}", file=sys.stderr)
        return 2

    npx = "npx.cmd" if sys.platform == "win32" else "npx"
    try:
        proc = subprocess.run(
            [npx, "vitest", "run", "--reporter=dot", *SUITES],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
        )
    except OSError as exc:
        print(f"UNAVAILABLE: could not run vitest ({exc.__class__.__name__})", file=sys.stderr)
        return 2

    tail = (proc.stdout or "")[-1200:]
    print(tail)
    if proc.returncode == 0:
        return 0
    # A non-zero exit with no recognisable summary means the runner itself failed, which is
    # "unavailable", not "the behaviour regressed" — the two must not read the same.
    return 1 if "Test Files" in (proc.stdout or "") else 2


if __name__ == "__main__":
    sys.exit(main())
