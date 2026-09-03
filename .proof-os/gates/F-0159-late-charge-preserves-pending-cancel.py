#!/usr/bin/env python3
"""
proof-os gate for F-0159 (delayed-charge-clears-pending-cancel).

THE DEFECT. SubscriptionService#applySubscriptionWebhookUpdate maps BOTH
`subscription.activated` and `subscription.charged` to SubscriptionStatus.ACTIVE - the event
type is not a parameter. The ACTIVE branch clears `cancelAtPeriodEnd`. A cancel-at-period-end
row deliberately STAYS ACTIVE and keeps billing until its period elapses, so the final cycle's
renewal charge - or a `charged` retry queued during an outage - legitimately lands on an ACTIVE,
flag-set row AHEAD of the terminal `subscription.cancelled`. Clearing the flag on THAT delivery
silently un-cancels a customer who did cancel: SubscriptionRenewalResetJob partitions solely on
`isCancelAtPeriodEnd()`, so the row is renewed instead of finalized and GET /billing/plan reports
cancelAtPeriodEnd=false.

MISSED BY (the ledger's own words, and the specification for this gate):
    "a test for a late 'charged' webhook landing on an already-cancel-pending row"

The suite has the MIRROR test - testReSubscribeAfterLapsedCancellationClearsCancelAtPeriodEnd
Flag - which proves the flag IS cleared on a genuine reactivation. Nothing proves it is
PRESERVED on the delivery that must not clear it, so the discriminator that tells those two
deliveries apart is unpinned: widen it back to an unconditional clear and every existing test
still passes.

THREE LEGS.
  A. STRUCTURE. Inside applySubscriptionWebhookUpdate, the before-state must be captured ahead
     of `subscription.setStatus(targetStatus)`, and the `setCancelAtPeriodEnd(false)` inside the
     `targetStatus == ACTIVE` branch must be GUARDED by a condition derived from that
     before-state - never a statement sitting directly in the ACTIVE branch.
  B. COVERAGE. SubscriptionServiceTest must contain a test method that, in this order, marks a
     row cancel-pending, drives applySubscriptionWebhookUpdate with ACTIVE while the row is
     still ACTIVE (no finalize / CANCELLED in between - that is the OTHER scenario), and then
     asserts isCancelAtPeriodEnd() is STILL true. Ordering is load-bearing: the existing
     re-subscribe test contains an assertTrue(sub.isCancelAtPeriodEnd()) too, but BEFORE its
     webhook call, as the bug's precondition - a bare substring grep greens vacuously on it.
  C. BEHAVIOUR. That test method is executed. A structural check alone cannot tell a real
     assertion from a commented-out one.

Every source read goes through gates/_strip_comments.py, and string literals are masked before
brace matching (`log.warn("...={}...")` in this very method would otherwise unbalance it).

Usage: python .proof-os/gates/F-0159-late-charge-preserves-pending-cancel.py
Exit:  0 proved (defect absent) - 1 broken (defect present) - 2 unavailable (tool could not run)
Env:   PROOF_F0159_TIMEOUT - wall-clock budget in seconds for leg C (default 900)
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

GATES = Path(__file__).resolve().parent
ROOT = GATES.parent.parent
STRIP = GATES / "_strip_comments.py"

SRC = ROOT / "influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java"
TEST = ROOT / "influora-api/src/test/java/com/influora/service/billing/SubscriptionServiceTest.java"
TEST_CLASS = "SubscriptionServiceTest"

NOT_CHECKED = (
    "NOT CHECKED: that Razorpay actually delivers `charged` before `cancelled` in production (no real\n"
    "             webhook replay is exercised here); the terminal subscription.cancelled path and\n"
    "             SubscriptionRenewalResetJob's own partitioning, both of which live outside this method;\n"
    "             whether the guard behaves for a row whose stored razorpaySubscriptionId is null; and any\n"
    "             other caller that writes cancelAtPeriodEnd."
)


def unavailable(why: str) -> None:
    print("- " + why)
    print("VERDICT: unavailable - the gate could not run, this is NOT a finding")
    print("NOT CHECKED: everything below the point of failure.")
    sys.exit(2)


def broken(lines) -> None:
    for line in lines:
        print(line)
    print(NOT_CHECKED)
    sys.exit(1)


def code_of(path: Path) -> str:
    """Comment-free view of a .java file, line numbers preserved."""
    if not path.is_file():
        unavailable(str(path) + " missing - unavailable")
    if not STRIP.is_file():
        unavailable("comment stripper missing at " + str(STRIP) + " - cannot tell code from comment")
    try:
        proc = subprocess.run(
            [sys.executable, str(STRIP), "--lang", "java", str(path)],
            capture_output=True, text=True, timeout=120,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        unavailable("comment stripper failed on " + path.name + ": " + str(exc))
    if proc.returncode != 0:
        unavailable(
            "comment stripper exited "
            + str(proc.returncode)
            + " on "
            + path.name
            + ": "
            + (proc.stderr or "").strip()[:300]
        )
    return proc.stdout


def mask_literals(text: str) -> str:
    """Blank out "..." / '...' contents, preserving length and newlines.

    Brace matching cannot survive `log.warn("razorpaySubscriptionId={}, ...")` otherwise: those
    `{}` are inside this very method and would close the block early.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"' or ch == "'":
            quote = ch
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote or text[j] == "\n":
                    break
                j += 1
            for k in range(i + 1, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
            continue
        i += 1
    return "".join(out)


def block_after(masked: str, start: int):
    """Span of the {...} block whose opening brace is the first one at/after `start`."""
    open_idx = masked.find("{", start)
    if open_idx < 0:
        return None
    depth = 0
    for i in range(open_idx, len(masked)):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                return (open_idx, i)
    return None


def paren_span(text: str, open_paren_idx: int):
    """Span (inner_start, close_idx) of the (...) starting at open_paren_idx."""
    depth = 0
    for i in range(open_paren_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return (open_paren_idx + 1, i)
    return None


def line_of(text: str, idx: int) -> int:
    return text.count("\n", 0, idx) + 1


# --------------------------------------------------------------------------- leg A
def leg_a() -> None:
    print("- structure: the ACTIVE branch's cancelAtPeriodEnd clear must be guarded by the row's before-state")
    src = code_of(SRC)
    masked = mask_literals(src)

    m = re.search(r"\bvoid\s+applySubscriptionWebhookUpdate\s*\(", masked)
    if not m:
        unavailable(
            "applySubscriptionWebhookUpdate not found in the CODE of SubscriptionService.java - the "
            "method this record names was renamed or removed; a human must re-point this gate"
        )
    span = block_after(masked, m.end())
    if span is None:
        unavailable("could not brace-match the body of applySubscriptionWebhookUpdate - unavailable")
    body_lo, body_hi = span
    body = masked[body_lo:body_hi]

    set_status = re.search(r"subscription\.setStatus\s*\(\s*targetStatus\s*\)", body)
    if not set_status:
        unavailable(
            "no `subscription.setStatus(targetStatus)` inside applySubscriptionWebhookUpdate - the "
            "method no longer has the shape this gate reasons about"
        )

    before = body[: set_status.start()]
    missing = []
    for name, pat in (
        ("previousStatus", r"previousStatus\s*=\s*subscription\.getStatus\s*\("),
        (
            "previousRazorpaySubscriptionId",
            r"previousRazorpaySubscriptionId\s*=\s*subscription\.getRazorpaySubscriptionId\s*\(",
        ),
    ):
        if not re.search(pat, before):
            missing.append(name)
    if missing:
        broken(
            [
                "  missing before-state capture(s): " + ", ".join(missing),
                "VERDICT: broken - applySubscriptionWebhookUpdate does not capture the row's BEFORE-state ahead",
                "         of `subscription.setStatus(targetStatus)`. The event type is not a parameter here, so",
                "         the before-state is the ONLY thing that can tell a genuine (re)activation from a late",
                "         `charged` landing on a row whose cancel is still pending (F-0159).",
            ]
        )
    print("    before-state captured ahead of setStatus(targetStatus)")

    act = re.search(r"if\s*\(\s*targetStatus\s*==\s*SubscriptionStatus\.ACTIVE\s*\)", body)
    if not act:
        unavailable(
            "no `if (targetStatus == SubscriptionStatus.ACTIVE)` branch inside "
            "applySubscriptionWebhookUpdate - the branch this record cites is gone; a human must re-point "
            "this gate"
        )
    aspan = block_after(body, act.end())
    if aspan is None:
        unavailable("could not brace-match the ACTIVE branch - unavailable")
    a_lo, a_hi = aspan
    branch = body[a_lo : a_hi + 1]

    clears = list(re.finditer(r"setCancelAtPeriodEnd\s*\(\s*false\s*\)", branch))
    if not clears:
        # No clear at all in the ACTIVE branch: neither the defect nor the fix this gate models,
        # so it must not claim a proof over a method it no longer understands.
        unavailable(
            "the ACTIVE branch no longer clears cancelAtPeriodEnd at all - neither the defect nor the "
            "fix this gate models; a human must re-read the method"
        )

    for c in clears:
        depth = branch.count("{", 0, c.start()) - branch.count("}", 0, c.start())
        abs_line = line_of(src, body_lo + a_lo + c.start())
        if depth <= 1:
            broken(
                [
                    "  SubscriptionService.java:"
                    + str(abs_line)
                    + ": setCancelAtPeriodEnd(false) sits directly in the ACTIVE branch",
                    "VERDICT: broken - every delivery that maps to ACTIVE clears cancelAtPeriodEnd, including a",
                    "         late/retried subscription.charged on a row whose cancel is still pending. That",
                    "         silently un-cancels a customer who did cancel (F-0159).",
                ]
            )
        guard_region = branch[: c.start()]
        if not ("previousStatus" in guard_region and "previousRazorpaySubscriptionId" in guard_region):
            broken(
                [
                    "  SubscriptionService.java:"
                    + str(abs_line)
                    + ": the clear is guarded, but by a condition that does not",
                    "  reference the row's before-state (previousStatus / previousRazorpaySubscriptionId)",
                    "VERDICT: broken - the guard cannot distinguish a genuine (re)activation from a late `charged`",
                    "         on an already-ACTIVE, cancel-pending row, which is the whole discriminator F-0159",
                    "         turns on.",
                ]
            )
    print("    clear is nested under a before-state guard (" + str(len(clears)) + " occurrence(s))")


# --------------------------------------------------------------------------- leg B
def leg_b():
    print("- coverage: a test must drive a late ACTIVE webhook onto a still-ACTIVE cancel-pending row")
    src = code_of(TEST)
    masked = mask_literals(src)

    qualifying = []
    near_misses = []
    for m in re.finditer(r"\bvoid\s+(\w+)\s*\(", masked):
        name = m.group(1)
        span = block_after(masked, m.end())
        if span is None:
            continue
        lo, hi = span
        body = masked[lo:hi]

        pend = re.search(r"setCancelAtPeriodEnd\s*\(\s*true\s*\)", body)
        if not pend:
            continue

        call = None
        for cm in re.finditer(r"applySubscriptionWebhookUpdate\s*\(", body):
            args = paren_span(body, cm.end() - 1)
            if args and "SubscriptionStatus.ACTIVE" in body[args[0] : args[1]]:
                call = cm
                break
        if call is None or call.start() < pend.start():
            continue

        # The row must still be ACTIVE when the webhook lands. A test that finalizes the row to
        # CANCELLED first is the re-subscribe scenario - the OPPOSITE assertion - and must not be
        # accepted as coverage for this one.
        between = body[pend.start() : call.start()]
        if re.search(r"finalizeLapsedCancellation\s*\(", between) or re.search(
            r"setStatus\s*\(\s*SubscriptionStatus\.CANCELLED", between
        ):
            near_misses.append(name + " (row is finalized to CANCELLED first - that is the re-subscribe case)")
            continue

        after = body[call.end() :]
        kept = False
        for am in re.finditer(r"assertTrue\s*\(", after):
            args = paren_span(after, am.end() - 1)
            if args and "isCancelAtPeriodEnd" in after[args[0] : args[1]]:
                kept = True
                break
        if not kept:
            near_misses.append(name + " (no assertTrue(...isCancelAtPeriodEnd()) AFTER the webhook call)")
            continue
        qualifying.append(name)

    if not qualifying:
        lines = ["  no qualifying test method in " + TEST_CLASS]
        for nm in near_misses:
            lines.append("    near miss: " + nm)
        lines += [
            "VERDICT: broken - nothing in the suite pins the discriminator. The only test touching this",
            "         branch proves the flag IS cleared on a genuine re-subscribe; no test proves it is",
            "         PRESERVED when a late/retried `charged` lands on a still-ACTIVE, cancel-pending row,",
            "         which is exactly the missing check this record names (F-0159). Widening the guard back",
            "         to an unconditional clear would leave the whole suite green.",
        ]
        broken(lines)

    print("    qualifying test(s): " + ", ".join(qualifying))
    return qualifying


# --------------------------------------------------------------------------- leg C
def leg_c(methods) -> None:
    try:
        budget = int(os.environ.get("PROOF_F0159_TIMEOUT", "900"))
    except ValueError:
        budget = 900
    api = ROOT / "influora-api"
    if not (api / "pom.xml").is_file():
        unavailable("no influora-api/pom.xml - cannot execute the regression test")
    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    selector = TEST_CLASS + "#" + "+".join(methods)
    print("- behaviour: mvn -o test -Dtest=" + selector + " (budget " + str(budget) + "s)")
    try:
        proc = subprocess.run(
            [mvn, "-o", "-B", "test", "-Dtest=" + selector, "-DfailIfNoTests=false"],
            cwd=str(api), capture_output=True, text=True, timeout=budget,
        )
    except FileNotFoundError:
        unavailable("mvn not on PATH - the structural legs passed but the behaviour leg could not run")
    except subprocess.TimeoutExpired:
        unavailable("the test run exceeded " + str(budget) + "s - unavailable, NOT a finding")
    out = (proc.stdout or "") + (proc.stderr or "")
    summary = [ln for ln in out.splitlines() if re.search(r"Tests run: \d+, Failures:", ln)]
    if not summary:
        if "COMPILATION ERROR" in out or "compilation failure" in out.lower():
            for ln in out.splitlines():
                if ln.startswith("[ERROR]") and ".java:" in ln:
                    print("    " + ln.strip()[:180])
                    break
            unavailable(
                "influora-api does not compile, so no test could run - that is a BUILD problem for "
                "gates/build.mvn.sh to report, never a finding for this record"
            )
        print(out[-1500:])
        unavailable("no surefire summary in the output - the test did not run to a result")
    print("    " + summary[-1].strip())
    if re.search(r"Tests run: 0,", summary[-1]):
        unavailable("surefire executed 0 tests for the selector - the assertion was not observed")
    if proc.returncode != 0:
        for ln in out.splitlines():
            if ln.startswith("[ERROR]") and TEST_CLASS in ln:
                print("    " + ln.strip()[:200])
        broken(
            [
                "VERDICT: broken - the regression test fails: a late/retried ACTIVE webhook on a cancel-pending",
                "         row clears cancelAtPeriodEnd and un-cancels a customer who did cancel (F-0159).",
            ]
        )


def main() -> None:
    if not ROOT.is_dir():
        unavailable("cannot resolve project root - unavailable")
    leg_a()
    methods = leg_b()
    leg_c(methods)
    print(
        "VERDICT: aligned (proved) - a late/retried `charged` delivery that maps to ACTIVE leaves a pending\n"
        "         cancel-at-period-end intact, and a test executes that path."
    )
    print(NOT_CHECKED)
    sys.exit(0)


if __name__ == "__main__":
    main()
