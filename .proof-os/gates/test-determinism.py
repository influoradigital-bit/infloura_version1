#!/usr/bin/env python3
"""
proof-os gate for the `flaky-test` class (F-0185, F-0217).

Both records are the same defect seen twice: the vitest suite passed alone and failed under CPU
contention, in a different combination each time. Measured before the fix — three concurrent
full-suite runs failed 14, 8 and 8 tests. After it, six consecutive concurrent runs failed none.

Two causes, both removed:

  1. Testing Library's async helpers default to a 1000ms timeout, which vitest's `testTimeout`
     does not govern. A page test still mounting at 1s failed with a message that reads like a
     real assertion failure ("Unable to find an accessible element..."), which is why these were
     repeatedly triaged as genuine breakage.
  2. `userEvent.setup()` inserts a real delay between every keystroke. That, not the timeout
     ceiling, was where the heavy suites spent their time — removing it cut one file's test time
     47%. Slow tests are what convert contention into a timeout.

WHAT THIS GATE PROVES: that neither cause has been reintroduced. It is a structural check and it
is fast.

WHAT IT DOES NOT PROVE: that the suite is deterministic. Only repeated runs show that, and no
single-pass check can. Use `--repeat N` for that (runs N full suites CONCURRENTLY, which is the
condition that exposed the original defect — running them in sequence does not).

Usage: python .proof-os/gates/test-determinism.py [--repeat N]
Exit:  0 clean · 1 a cause was reintroduced (or a --repeat run failed) · 2 sources unreadable
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
SETUP = ROOT / "src" / "test" / "setup.ts"
CONFIG = ROOT / "vitest.config.ts"
MIN_ASYNC_TIMEOUT = 5_000
MIN_TEST_TIMEOUT = 30_000


def _num(text: str) -> int:
    """`5_000` / `5000` -> 5000."""
    return int(text.replace("_", ""))


def structural_checks() -> tuple[list[str], bool]:
    findings: list[str] = []
    if not SETUP.exists() or not CONFIG.exists():
        return [f"UNREADABLE: {SETUP if not SETUP.exists() else CONFIG}"], True

    setup = SETUP.read_text(encoding="utf-8", errors="replace")
    config = CONFIG.read_text(encoding="utf-8", errors="replace")

    m = re.search(r"asyncUtilTimeout:\s*([\d_]+)", setup)
    if not m:
        findings.append(
            "src/test/setup.ts no longer calls configure({ asyncUtilTimeout }) — every findBy/"
            "waitFor is back on Testing Library's 1000ms default (cause 1 of F-0185/F-0217)"
        )
    elif _num(m.group(1)) < MIN_ASYNC_TIMEOUT:
        findings.append(
            f"asyncUtilTimeout is {m.group(1)}, below the {MIN_ASYNC_TIMEOUT}ms that made the "
            f"suite survive contention"
        )

    m = re.search(r"testTimeout:\s*([\d_]+)", config)
    if not m:
        findings.append("vitest.config.ts sets no testTimeout")
    elif _num(m.group(1)) < MIN_TEST_TIMEOUT:
        findings.append(
            f"testTimeout is {m.group(1)}, below {MIN_TEST_TIMEOUT}ms — with 5s async waits a "
            f"test doing several in sequence reports a timeout instead of the contention it was"
        )

    bare: list[str] = []
    for path in (ROOT / "src").rglob("*.test.ts*"):
        if "userEvent.setup()" in path.read_text(encoding="utf-8", errors="replace"):
            bare.append(str(path.relative_to(ROOT)))
    if bare:
        findings.append(
            "userEvent.setup() without `{ delay: null }` in "
            + ", ".join(sorted(bare)[:5])
            + (f" (+{len(bare) - 5} more)" if len(bare) > 5 else "")
            + " — the per-keystroke delay is cause 2"
        )

    return findings, False


def repeat_runs(n: int) -> int:
    npx = "npx.cmd" if sys.platform == "win32" else "npx"
    tmp = Path(tempfile.mkdtemp(prefix="determinism-"))

    def one(i: int) -> tuple[int, int]:
        out = tmp / f"run{i}.json"
        subprocess.run(
            [npx, "vitest", "run", "--reporter=json", f"--outputFile={out}"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
        )
        if not out.exists():
            return i, -1
        data = json.loads(out.read_text(encoding="utf-8", errors="replace"))
        return i, int(data.get("numFailedTests", -1))

    with ThreadPoolExecutor(max_workers=n) as pool:
        results = sorted(pool.map(one, range(1, n + 1)))

    bad = False
    for i, failed in results:
        if failed == -1:
            print(f"run {i}: UNREADABLE (no report)")
            bad = True
        else:
            print(f"run {i}: {failed} failed")
            bad = bad or failed > 0
    return 1 if bad else 0


def main() -> int:
    findings, unreadable = structural_checks()
    if unreadable:
        for f in findings:
            print(f, file=sys.stderr)
        return 2
    for f in findings:
        print(f"REGRESSION  {f}")
    if findings:
        return 1
    print("test-determinism: OK — both F-0185/F-0217 causes still absent")

    if "--repeat" in sys.argv:
        idx = sys.argv.index("--repeat")
        n = int(sys.argv[idx + 1]) if len(sys.argv) > idx + 1 else 3
        print(f"running {n} concurrent full suites...")
        return repeat_runs(n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
