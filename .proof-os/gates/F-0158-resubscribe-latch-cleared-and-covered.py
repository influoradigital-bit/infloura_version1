#!/usr/bin/env python3
"""
proof-os gate for F-0158 (one-way-flag-latch-in-money-path).

THE RECORD
    where:  influora-api/.../service/billing/SubscriptionService.java
            #applySubscriptionWebhookUpdate (~L512, "cancelAtPeriodEnd never cleared on
            re-activation")
    symptom BL-2's fix made Subscription.cancelAtPeriodEnd LOAD-BEARING for
            SubscriptionRenewalResetJob#doRun, which partitions its stale-period ACTIVE batch on
            isCancelAtPeriodEnd() ALONE: flag set -> finalizeLapsedCancellation, flag clear ->
            applyRenewalSafetyNet. The flag was a one-way latch - only #cancel ever SET it and
            only the brand-new-row builder ever cleared it - so a customer who cancelled and then
            RE-SUBSCRIBED kept cancelAtPeriodEnd=true forever, and the next missed/delayed webhook
            finalized their ACTIVE, currently-paying Pro row to CANCELLED while Razorpay kept
            charging them.

THE SPEC IS THE RECORD'S "missed_by" FIELD. It names three distinct missing checks:
    (a) "a re-subscribe -> missed-webhook regression test"
    (b1) "unstubbed planRepository.findById masking done_when clause 4"
    (b2) "missing period fields masking the no-side-effects assertion on the webhook cancel case"

WHY THIS GATE DOES NOT GREP THE FILE'S BYTES, AND DOES NOT MUTATE IT EITHER
    1. Bytes. The fix for this record ships a ~30-line comment that QUOTES
       `subscription.setCancelAtPeriodEnd(false)` five times while explaining it. Right now the
       CODE statement is gone from the file and only those comments remain, yet
       `grep setCancelAtPeriodEnd(false) SubscriptionService.java` still matches. A byte gate here
       is a guaranteed false GREEN (F-0266). Every read below therefore goes through
       gates/_strip_comments.py and is anchored to the ACTIVE branch of one named method - the
       identifier also lives in #cancel and in the new-row builder, where a bare substring grep
       would green vacuously.
    2. Mutation-in-place. The previous gate for this record
       (gates/F-0158-cancel-at-period-end-latch.sh) proved coverage by sed-ing a mutant into
       SubscriptionService.java and restoring it in an EXIT trap. That trap did not fire on one
       run, and the mutant `;//__MUT1__` is sitting in the money path in this working tree as this
       gate is written - i.e. the gate re-introduced the very defect it was built to detect and
       left it there. A gate must not be able to do that. Nothing below writes to any source file.

FIVE LEGS. A..D are structural and cheap and run first, collecting EVERY finding rather than
stopping at the first, so one run names all of what is missing. E is behavioural and is the
primary evidence: it EXECUTES the regression test leg B identified.

    A  MONEY PATH. Inside applySubscriptionWebhookUpdate's `targetStatus == ACTIVE` branch there
       must be a real CODE statement clearing cancelAtPeriodEnd. This is the defect itself.
    B  COVERAGE (missed_by a). SubscriptionServiceTest must contain a test that, IN THIS ORDER,
       marks a row cancel-pending, drives it to CANCELLED, drives applySubscriptionWebhookUpdate
       with ACTIVE, and only THEN asserts the flag is false. Ordering is load-bearing: the same
       test contains an assertTrue(isCancelAtPeriodEnd()) as the bug's precondition, so an
       unordered substring grep greens on the wrong assertion.
    C  NON-VACUITY (missed_by b1). That test must stub planRepository.findById - unstubbed, the
       mock returns Optional.empty() and getActivePlanForWorkspace falls through to Free either
       way, so the plan-derived half of the outcome is unobserved.
    D  NON-VACUITY (missed_by b2). Some test must drive a STATUS-ONLY delivery (a
       cancelled/halted/past_due webhook, which carries null periods) and then assert that
       currentPeriodStart AND currentPeriodEnd SURVIVED it. Subscription#renewPeriod does no
       null-checking, and currentPeriodEnd is exactly what SubscriptionRenewalResetJob#doRun
       dereferences to build its batch - the same routing this record is about.
    E  BEHAVIOUR. Run leg B's test. A structural check cannot tell a live assertion from a
       commented-out one, and cannot see a defect in the code the assertion covers.

Usage: python .proof-os/gates/F-0158-resubscribe-latch-cleared-and-covered.py
Exit:  0 proved (defect absent) | 1 broken (defect present) | 2 unavailable (tool could not run)
Env:   PROOF_F0158_TIMEOUT - wall-clock budget in seconds for leg E (default 900)
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

API = ROOT / "influora-api"
SRC = API / "src/main/java/com/influora/service/billing/SubscriptionService.java"
TEST = API / "src/test/java/com/influora/service/billing/SubscriptionServiceTest.java"
TEST_CLASS = "SubscriptionServiceTest"
METHOD = "applySubscriptionWebhookUpdate"

# Every status this enum has that is NOT ACTIVE. A status-only Razorpay delivery
# (pending/halted/cancelled) carries no billing period, which is what leg D is about.
NON_ACTIVE = ("CANCELLED", "HALTED", "PAST_DUE", "PENDING")

NOT_CHECKED_TAIL = [
    "NOT CHECKED: (1) other one-way latches on this row - grantAdminPlan's existing-row branch also never",
    "             clears cancelAtPeriodEnd, and is safe today only because #cancel requires a non-null",
    "             razorpaySubscriptionId while grantAdminPlan rejects those; that implicit invariant is",
    "             outside this gate. (2) SubscriptionRenewalResetJob#doRun's own partitioning - this gate",
    "             asserts the INPUT that job reads, never runs the job. (3) unit scope only: no live",
    "             Razorpay delivery, no real scheduler tick, no DB constraint, no ordering guarantee that",
    "             production actually delivers activated/charged/cancelled the way the fixtures assume.",
    "             (4) whether the guard around the clear is itself correct for a LATE charged delivery -",
    "             that is F-0159's record and F-0159's gate, deliberately not re-litigated here.",
]


def unavailable(why: str, extra=()) -> None:
    print("- " + why)
    for line in extra:
        print("  " + line)
    print("VERDICT: unavailable - the gate could not run. This is NOT a finding.")
    print("NOT CHECKED: everything at and below the point of failure.")
    sys.exit(2)


# ------------------------------------------------------------------ comment-free source views
def code_of(path: Path) -> str:
    """The CODE of a .java file - comments blanked, line numbers and length preserved."""
    if not path.is_file():
        unavailable(str(path) + " is missing - the file this record names is not here")
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
            "comment stripper exited %d on %s: %s"
            % (proc.returncode, path.name, (proc.stderr or "").strip()[:300])
        )
    return proc.stdout


def mask_literals(text: str) -> str:
    """Blank the CONTENTS of "..." / '...', preserving length and newlines.

    Brace matching cannot survive the `log.warn("...={}, ...={}")` calls inside this very method
    otherwise - those braces would close the block early and the gate would reason about the
    wrong span.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch in ('"', "'"):
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == ch or text[j] == "\n":
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
    """Span (open_idx, close_idx) of the {...} whose opening brace is the first at/after start."""
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


def paren_span(text: str, open_idx: int):
    """Span (inner_start, close_idx) of the (...) whose '(' is at open_idx."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return (open_idx + 1, i)
    return None


def line_of(text: str, idx: int) -> int:
    return text.count("\n", 0, idx) + 1


def methods_of(masked: str):
    """[(name, body_lo, body_hi)] for every `... name(...) { ... }` in a masked class body."""
    found = []
    for m in re.finditer(r"\b(?:void|boolean|int|String|Instant|Subscription|Plan)\s+(\w+)\s*\(", masked):
        span = block_after(masked, m.end())
        if span is None:
            continue
        found.append((m.group(1), span[0], span[1]))
    return found


# --------------------------------------------------------------------------------------- leg A
def leg_a(findings) -> None:
    print("- leg A  money path: the ACTIVE branch of " + METHOD + " must CLEAR cancelAtPeriodEnd")
    src = code_of(SRC)
    masked = mask_literals(src)

    m = re.search(r"\bvoid\s+" + METHOD + r"\s*\(", masked)
    if not m:
        unavailable(
            METHOD + " not found in the CODE of SubscriptionService.java - the method this record "
            "names was renamed or removed, and a human must re-point this gate rather than let it "
            "guess"
        )
    span = block_after(masked, m.end())
    if span is None:
        unavailable("could not brace-match the body of " + METHOD)
    body_lo, body_hi = span
    body = masked[body_lo:body_hi]

    act = re.search(r"if\s*\(\s*targetStatus\s*==\s*SubscriptionStatus\.ACTIVE\s*\)", body)
    if not act:
        unavailable(
            "no `if (targetStatus == SubscriptionStatus.ACTIVE)` branch inside " + METHOD + " - the "
            "branch this record cites is gone; a human must re-read the method"
        )
    aspan = block_after(body, act.end())
    if aspan is None:
        unavailable("could not brace-match the ACTIVE branch of " + METHOD)
    a_lo, a_hi = aspan
    branch = body[a_lo:a_hi + 1]
    branch_line = line_of(src, body_lo + a_lo)

    clears = list(re.finditer(r"\.\s*setCancelAtPeriodEnd\s*\(\s*false\s*\)", branch))
    if not clears:
        findings.append([
            "  SubscriptionService.java:%d: the ACTIVE branch contains NO code that clears"
            % branch_line,
            "  cancelAtPeriodEnd (searched the branch's CODE only - the identifier does appear in this",
            "  method's comments, and in #cancel as setCancelAtPeriodEnd(TRUE), neither of which counts).",
            "  BROKEN (missed_by a, the defect itself): cancelAtPeriodEnd is still a one-way latch. A",
            "  workspace that cancels and then re-subscribes stays flagged forever, and because",
            "  SubscriptionRenewalResetJob#doRun partitions its stale-period ACTIVE batch on",
            "  isCancelAtPeriodEnd() alone, the next missed/delayed webhook routes that currently-paying",
            "  row to finalizeLapsedCancellation - revoking Pro while Razorpay keeps charging.",
        ])
        return

    # Report, but do not re-litigate, the guard shape: whether the clear correctly EXCLUDES a late
    # `charged` on an already-ACTIVE row is F-0159's record and F-0159's gate.
    guarded = 0
    for c in clears:
        depth = branch.count("{", 0, c.start()) - branch.count("}", 0, c.start())
        if depth > 1:
            guarded += 1
    print("    found %d clear(s) in the ACTIVE branch (%d nested under a guard) at L%d+"
          % (len(clears), guarded, branch_line))


# --------------------------------------------------------------------------------------- leg B/C
def leg_bc(findings):
    print("- leg B  coverage: a re-subscribe -> missed-webhook regression test, in the right ORDER")
    src = code_of(TEST)
    masked = mask_literals(src)

    qualifying, near = [], []
    for name, lo, hi in methods_of(masked):
        body = masked[lo:hi]

        pend = re.search(r"setCancelAtPeriodEnd\s*\(\s*true\s*\)", body)
        if not pend:
            continue

        # The row must be driven to CANCELLED between the flag being set and the ACTIVE webhook.
        # Without that step this is the OTHER scenario (a late charge on a still-ACTIVE
        # cancel-pending row, F-0159), whose correct assertion is the OPPOSITE of this one.
        call = None
        for cm in re.finditer(METHOD + r"\s*\(", body):
            args = paren_span(body, cm.end() - 1)
            if args and "SubscriptionStatus.ACTIVE" in body[args[0]:args[1]]:
                if cm.start() > pend.start():
                    call = cm
                    break
        if call is None:
            continue

        between = body[pend.start():call.start()]
        if not (re.search(r"finalizeLapsedCancellation\s*\(", between)
                or re.search(r"SubscriptionStatus\.CANCELLED", between)):
            near.append(name + ": row is never driven to CANCELLED before the ACTIVE webhook "
                               "(that is F-0159's late-charge case, not a re-subscribe)")
            continue

        # assertFalse(...isCancelAtPeriodEnd()) must come AFTER the webhook call. The same method
        # asserts the flag is TRUE beforehand, as the bug's precondition, so position is the whole
        # discriminator between a real regression test and a vacuous grep hit.
        after = body[call.end():]
        cleared = False
        for am in re.finditer(r"assertFalse\s*\(", after):
            args = paren_span(after, am.end() - 1)
            if args and "isCancelAtPeriodEnd" in after[args[0]:args[1]]:
                cleared = True
                break
        if not cleared:
            near.append(name + ": no assertFalse(...isCancelAtPeriodEnd()) AFTER the ACTIVE webhook call")
            continue
        qualifying.append((name, body))

    if not qualifying:
        lines = ["  no qualifying test method in " + TEST_CLASS]
        for nm in near:
            lines.append("    near miss: " + nm)
        lines += [
            "  BROKEN (missed_by a): nothing drives cancel -> lapse-finalized CANCELLED -> re-subscribe",
            "  (ACTIVE webhook) and then asserts the latch was released. Re-introducing the one-way latch",
            "  in the money path would leave the whole billing suite green.",
        ]
        findings.append(lines)
    else:
        print("    qualifying: " + ", ".join(n for n, _ in qualifying))

    print("- leg C  non-vacuity: that test must stub planRepository.findById (mutation-testing finding)")
    unstubbed = [n for n, b in qualifying if not re.search(r"planRepository\s*\.\s*findById\s*\(", b)]
    if unstubbed:
        findings.append([
            "  " + ", ".join(unstubbed) + ": no planRepository.findById(...) stub",
            "  BROKEN (missed_by b1): unstubbed, that mock returns Optional.empty() and",
            "  getActivePlanForWorkspace falls through to the Free plan whether or not its ACTIVE filter",
            "  is present - so the plan-derived half of the outcome is asserted VACUOUSLY.",
        ])
    elif qualifying:
        print("    stubbed in all qualifying test(s)")

    return [n for n, _ in qualifying]


# --------------------------------------------------------------------------------------- leg D
def leg_d(findings) -> None:
    print("- leg D  non-vacuity: a STATUS-ONLY webhook must be asserted to leave the billing period alone")
    src = code_of(TEST)
    masked = mask_literals(src)

    ok, near = [], []
    for name, lo, hi in methods_of(masked):
        body = masked[lo:hi]

        # A status-only delivery: a non-ACTIVE target status AND null period arguments. Those two
        # nulls are what make Subscription#renewPeriod dangerous here - it assigns straight through
        # with no null check, so an unguarded call would NULL a live row's period.
        call = None
        for cm in re.finditer(METHOD + r"\s*\(", body):
            args = paren_span(body, cm.end() - 1)
            if not args:
                continue
            arg_text = body[args[0]:args[1]]
            if not any(("SubscriptionStatus." + s) in arg_text for s in NON_ACTIVE):
                continue
            if not re.search(r"null\s*,\s*null", arg_text):
                continue
            call = cm
            break
        if call is None:
            continue

        after = body[call.end():]
        saw_start = saw_end = False
        for am in re.finditer(r"assert\w*\s*\(", after):
            args = paren_span(after, am.end() - 1)
            if not args:
                continue
            arg_text = after[args[0]:args[1]]
            if "getCurrentPeriodStart" in arg_text:
                saw_start = True
            if "getCurrentPeriodEnd" in arg_text:
                saw_end = True
        if saw_start and saw_end:
            ok.append(name)
        else:
            missing = []
            if not saw_start:
                missing.append("currentPeriodStart")
            if not saw_end:
                missing.append("currentPeriodEnd")
            near.append(name + ": drives a status-only webhook but never asserts " + " / ".join(missing)
                        + " survived it")

    if not ok:
        lines = ["  no test asserts the no-side-effects contract on a status-only delivery"]
        for nm in near:
            lines.append("    near miss: " + nm)
        lines += [
            "  BROKEN (missed_by b2): the fixture now carries period fields, but nothing asserts they",
            "  SURVIVE a cancelled/halted webhook. Subscription#renewPeriod null-checks nothing, so",
            "  deleting the `if (periodStart != null && periodEnd != null)` guard in " + METHOD + " would",
            "  NULL currentPeriodStart/currentPeriodEnd on a live paying row with the suite still green -",
            "  and currentPeriodEnd is precisely what SubscriptionRenewalResetJob#doRun dereferences to",
            "  build the stale-period batch this record's failure mode runs through.",
        ]
        findings.append(lines)
    else:
        print("    asserted in: " + ", ".join(ok))


# --------------------------------------------------------------------------------------- leg E
def leg_e(methods):
    """Run leg B's regression test. Returns 'GREEN' | 'RED' | ('INFRA', why)."""
    try:
        budget = int(os.environ.get("PROOF_F0158_TIMEOUT", "900"))
    except ValueError:
        budget = 900
    if not (API / "pom.xml").is_file():
        return ("INFRA", "no influora-api/pom.xml - the backend module is not here")

    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    selector = TEST_CLASS + "#" + "+".join(methods)
    print("- leg E  behaviour: mvn -o test -Dtest=" + selector + "  (budget " + str(budget) + "s)")

    # This working copy is shared with other automation that runs `mvn clean` (see the project's
    # concurrent-session note). A wiped target/ mid-run surfaces as NoClassDefFoundError on a
    # class that compiled seconds earlier - an infrastructure race, NOT a killed assertion, and it
    # must never be reported red. Same for the ByteBuddy/Mockito instrumentation flake this
    # backend hits on Windows. Both are retried, then escalated to unavailable.
    last = ""
    for attempt in (1, 2, 3):
        try:
            proc = subprocess.run(
                [mvn, "-o", "-B", "test", "-Dtest=" + selector, "-DfailIfNoTests=false"],
                cwd=str(API), capture_output=True, text=True, timeout=budget,
            )
        except FileNotFoundError:
            return ("INFRA", "mvn is not on PATH - the structural legs ran, the behaviour leg did not")
        except subprocess.TimeoutExpired:
            return ("INFRA", "the test run exceeded " + str(budget) + "s")
        out = (proc.stdout or "") + (proc.stderr or "")
        last = out

        if re.search(r"NoClassDefFound|Could not modify all classes|Mockito cannot mock this class"
                     r"|Byte Buddy could not instrument", out):
            print("    attempt %d: build-artifact / instrumentation race - retrying" % attempt)
            continue
        if "COMPILATION ERROR" in out or "compilation failure" in out.lower():
            for ln in out.splitlines():
                if ln.startswith("[ERROR]") and ".java:" in ln:
                    print("    " + ln.strip()[:180])
                    break
            return ("INFRA", "influora-api does not compile, so no test could run - that is a BUILD "
                             "problem for the build gate to report, never a finding for this record")

        summary = [ln for ln in out.splitlines() if re.search(r"Tests run: \d+, Failures:", ln)]
        if not summary:
            return ("INFRA", "maven printed no surefire summary - the test never reached a result")
        print("    " + summary[-1].strip())
        if re.search(r"Tests run: 0,", summary[-1]):
            return ("INFRA", "surefire executed 0 tests for the selector - the assertion was not observed")
        if proc.returncode == 0:
            return "GREEN"
        for ln in out.splitlines():
            if "AssertionFailedError" in ln or (ln.startswith("[ERROR]") and TEST_CLASS + "." in ln):
                print("    " + ln.strip()[:220])
                break
        return "RED"

    del last
    return ("INFRA", "all 3 attempts hit a build-artifact / instrumentation race")


# --------------------------------------------------------------------------------------- main
def main() -> None:
    if not ROOT.is_dir():
        unavailable("cannot resolve the project root")

    findings = []
    leg_a(findings)
    methods = leg_bc(findings)
    leg_d(findings)

    behaviour, why = None, ""
    if methods:
        r = leg_e(methods)
        if isinstance(r, tuple):
            why = r[1]
        else:
            behaviour = r
    else:
        why = "leg B found no regression test to execute"

    if behaviour == "RED":
        findings.append([
            "  " + TEST_CLASS + "#" + ", ".join(methods) + " FAILS on this tree",
            "  BROKEN (behavioural, the strongest evidence here): the re-subscribe regression test runs and",
            "  its assertion does not hold - cancelAtPeriodEnd is NOT cleared when a cancelled workspace",
            "  re-subscribes, so the latch is live in the money path right now.",
        ])

    if findings:
        for block in findings:
            for line in block:
                print(line)
        print("VERDICT: broken - " + str(len(findings)) + " finding(s) above. F-0158's one-way "
              "cancelAtPeriodEnd latch")
        print("         and/or the checks its ledger record says were missing are absent from this tree.")
        if behaviour is None:
            print("NOTE: the behaviour leg did not reach a result (" + why + "), so the findings above are")
            print("      structural. That does not soften them - each is determined from the file's CODE.")
        for line in NOT_CHECKED_TAIL:
            print(line)
        sys.exit(1)

    if behaviour is None:
        unavailable(
            "every structural leg passed, but the behaviour leg could not run: " + why,
            extra=["a structural pass alone cannot tell a live assertion from a commented-out one,",
                   "so this gate refuses to call the defect absent on structure alone."],
        )

    print("VERDICT: proved - cancelAtPeriodEnd is cleared on (re)activation inside " + METHOD + "'s")
    print("         ACTIVE branch; a correctly-ordered re-subscribe -> missed-webhook regression test")
    print("         covers it and PASSES; that test stubs planRepository.findById so its plan-derived")
    print("         half is not vacuous; and a status-only webhook is asserted to leave the billing")
    print("         period intact.")
    for line in NOT_CHECKED_TAIL:
        print(line)
    sys.exit(0)


if __name__ == "__main__":
    main()
