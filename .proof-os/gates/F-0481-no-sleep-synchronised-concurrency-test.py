#!/usr/bin/env python3
"""
proof-os gate for the backend half of the `flaky-test` class (F-0480, F-0481).

`test-determinism.py` already closes this class for the VITEST suite, and it is deliberately
frontend-specific: the two causes it forbids (Testing Library's 1000ms default and userEvent's
inter-keystroke delay) do not exist in JUnit. So a Java test of the identical class was invisible
to every gate, which is how F-0480 shipped.

THE DEFECT, measured: eight JUnit overlap-guard tests synchronised two threads with a fixed
`Thread.sleep(10)` after `Thread.start()`, then asserted `verify(..., times(1))`. Under a
contended full-suite run the spawned thread has not always entered the guarded section within
10ms, both calls proceed, and the assertion fails against a guard that was working perfectly.
Two separate full-suite runs this session failed on two DIFFERENT members of that family
(AudienceDemographicsJobTest, then SubscriptionDunningJobTest) while each passed alone — the
"different combination each time" signature F-0185 already recorded for the frontend.

WHAT THIS GATE PROVES: that no test synchronises on a duration where it should synchronise on a
fact. A test that starts a thread must wait for a latch/barrier/future (`.await(`), not sleep and
hope.

WHAT IT DOES NOT PROVE: that the backend suite is deterministic. Only repeated concurrent runs
show that, and no static check can. It also does not judge `Thread.sleep` used to SIMULATE slow
work INSIDE a stub — that is legitimate and is exactly what makes the guarded section observable;
only sleeps on the test's own main path after `.start()` are forbidden.

Exit: 0 clean · 1 a sleep-synchronised concurrency test exists · 2 sources unreadable
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
TEST_ROOT = ROOT / "influora-api" / "src" / "test" / "java"

# F-0266: match CODE, not file bytes — this gate's own docstring names the pattern it forbids.
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
METHOD = re.compile(r"void\s+(\w+)\s*\([^)]*\)[^{]*(?=\{)")
SLEEP = re.compile(r"Thread\.sleep\(")


def strip_comments(src: str) -> str:
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", src))


def method_body(src: str, start: int) -> str:
    """Brace-matched body. A naive fixed-width slice bleeds into the NEXT method and reports
    violations in innocent ones — measured: it turned 7 real findings into 41."""
    depth = 0
    open_at = src.index("{", start)
    for i in range(open_at, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_at : i + 1]
    return src[open_at:]


def main() -> int:
    if not TEST_ROOT.is_dir():
        print(f"· {TEST_ROOT} not found — unavailable")
        return 2

    try:
        files = sorted(TEST_ROOT.rglob("*.java"))
    except OSError as e:
        print(f"· cannot walk test sources ({e}) — unavailable")
        return 2

    if not files:
        print("· no Java test sources found — unavailable")
        return 2

    violations = []
    for path in files:
        try:
            code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        except OSError as e:
            print(f"· cannot read {path} ({e}) — unavailable")
            return 2

        for m in METHOD.finditer(code):
            body = method_body(code, m.end())
            if ".start()" not in body:
                continue
            after_start = body.split(".start()", 1)[1]
            # A latch/barrier/future anywhere in the method means it waits on a fact.
            if ".await(" in body or ".get(" in body:
                continue
            if SLEEP.search(after_start):
                violations.append((path.relative_to(ROOT), m.group(1)))

    print(f"· scanned {len(files)} Java test file(s) for sleep-as-synchronisation")

    if violations:
        for rel, name in violations:
            print(f"  {rel} :: {name}")
        print(
            "VERDICT: broken — the above start a thread and then sleep on the main path instead of"
        )
        print(
            "  awaiting a latch. Each is a latent full-suite flake that will surface under CPU"
        )
        print(
            "  contention and be triaged as real breakage first. Fix: CountDownLatch counted down"
        )
        print("  inside the stub that runs while the guard is held, awaited instead of the sleep.")
        return 1

    print("VERDICT: proved — no test synchronises on a duration after starting a thread.")
    print(
        "NOT CHECKED: that the backend suite is deterministic — only repeated CONCURRENT full-suite"
    )
    print(
        "  runs show that, and this is a static check; sleeps inside stubs (legitimate: they make"
    )
    print(
        "  the guarded section observable); and any flakiness with a cause other than thread"
    )
    print("  synchronisation, e.g. shared mutable static state or clock/timezone dependence.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
