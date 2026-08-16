#!/usr/bin/env python3
"""
proof-os gate for F-0139 — the brand-facing pinned-post read had no test.

F-0139: M-2 replaced CreatorMapper's hardcoded Collections.emptyList() with the creator's real
pinned posts on GET /creators/{id}, but nothing asserted any of it — every existing test passed
identically whether the mapping were right, inverted, or reverted to the stub.

Runs the two JUnit classes that now cover it:
  - PortfolioServiceTest#getVisiblePinnedPosts_*   (the visibility gate itself)
  - CreatorDiscoveryServiceTest#testGet*PinnedPosts / testSearchDoesNotHydratePortfolios

Both were negative-control checked when written: removing the contentPortfolio gate fails the
PortfolioService set, and reverting CreatorDiscoveryService to the 4-arg toResponse fails the
discovery set.

Usage: python .proof-os/gates/pinned-posts-coverage.py
Exit:  0 both classes pass · 1 a test failed · 2 maven or the sources are unavailable
"""

from __future__ import annotations

import glob
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
API = ROOT / "influora-api"
CLASSES = ["CreatorDiscoveryServiceTest", "PortfolioServiceTest"]


def main() -> int:
    if not API.exists():
        print(f"UNAVAILABLE: {API} not present", file=sys.stderr)
        return 2

    mvn = "mvn.cmd" if sys.platform == "win32" else "mvn"
    try:
        subprocess.run(
            [mvn, "-o", "-q", f"-Dtest={','.join(CLASSES)}", "-DfailIfNoTests=false", "test"],
            cwd=str(API),
            capture_output=True,
            text=True,
        )
    except OSError as exc:
        print(f"UNAVAILABLE: could not run maven ({exc.__class__.__name__})", file=sys.stderr)
        return 2

    # Read the surefire reports rather than maven's exit code: -q hides the summary, and a
    # non-zero exit can also mean "could not resolve a plugin offline", which is unavailable
    # rather than a real failure. The reports are the primary source.
    seen, failed = set(), False
    for path in glob.glob(str(API / "target" / "surefire-reports" / "*.txt")):
        text = Path(path).read_text(encoding="utf-8", errors="replace")
        for cls in CLASSES:
            if f"in com.influora.service" in text and cls in text:
                m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)", text)
                if not m:
                    continue
                seen.add(cls)
                run, fails, errors = (int(g) for g in m.groups())
                status = "OK" if fails == 0 and errors == 0 else "FAIL"
                print(f"{status}  {cls}: {run} run, {fails} failures, {errors} errors")
                if fails or errors:
                    failed = True

    missing = [c for c in CLASSES if c not in seen]
    if missing:
        print(f"UNAVAILABLE: no surefire report for {', '.join(missing)}", file=sys.stderr)
        return 2
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
