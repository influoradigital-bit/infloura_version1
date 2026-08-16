#!/usr/bin/env python3
"""
proof-os gate for F-0146 — the MONEY_TOOL_SCOPE_DECLINE branch had no test.

F-0146: nothing in influora-ai/tests referenced MONEY_TOOL_SCOPE_DECLINE or
money_tool_scope_declined. The branch matches error codes raised in a DIFFERENT language
(OnBehalfAuthResolver.java), so a rename on the Java side breaks it silently — the branch stops
firing, the loop falls through to the freestyle path, and Meera narrates a 403 she cannot act on.

Runs tests/tools/test_loop_money_tool_scope_decline.py, which covers the branch for all three
on-behalf codes, the two cases it must NOT catch, and — importantly — asserts the three codes
still exist in the Java source, which is the only assertion that can catch the rename.

Both halves were negative-control checked: narrowing the branch fails 6 tests, and renaming a
code in OnBehalfAuthResolver.java fails the coupling guard specifically.

Usage: python .proof-os/gates/money-tool-decline-coverage.py
Exit:  0 pass · 1 a test failed · 2 pytest/venv unavailable or the suite is missing
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
AI = ROOT / "influora-ai"
SUITE = "tests/tools/test_loop_money_tool_scope_decline.py"


def _python() -> str:
    venv = AI / ".venv" / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")
    return str(venv) if venv.exists() else sys.executable


def main() -> int:
    if not (AI / SUITE).exists():
        print(f"UNAVAILABLE: {AI / SUITE} is missing", file=sys.stderr)
        return 2

    try:
        proc = subprocess.run(
            [_python(), "-m", "pytest", SUITE, "-q", "--no-header"],
            cwd=str(AI),
            capture_output=True,
            text=True,
        )
    except OSError as exc:
        print(f"UNAVAILABLE: could not run pytest ({exc.__class__.__name__})", file=sys.stderr)
        return 2

    out = (proc.stdout or "") + (proc.returncode and (proc.stderr or "") or "")
    summary = [l for l in out.splitlines() if "passed" in l or "failed" in l or "error" in l]
    print("\n".join(summary[-3:]) if summary else out[-500:])

    # A skipped coupling guard is NOT a pass: it means the Java source could not be found, so the
    # cross-language assertion never ran. Fail closed — that is the exact blind spot F-0146 is about.
    if "skipped" in out:
        print("UNAVAILABLE: a test was skipped — the Java coupling guard did not run", file=sys.stderr)
        return 2
    if proc.returncode == 0:
        return 0
    return 1 if "failed" in out else 2


if __name__ == "__main__":
    sys.exit(main())
