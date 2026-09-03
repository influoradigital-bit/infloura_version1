#!/usr/bin/env python3
"""
proof-os gate for F-0162 (class: reclaim-op-outside-ops-bean-deadlocks).

THE DEFECT. `IdempotencyService.executeOnce` / `.runExclusive` reserve the idempotency row through
`IdempotencyReservationOps` -- a SEPARATE Spring bean whose every write method carries
`@Transactional(propagation = REQUIRES_NEW)`, so each write happens in its own short-lived
transaction and the caller's ambient transaction never touches the row directly. Four of the five
writes (tryReserve / markCompleted / markFailed / release) were moved onto that bean; the fifth,
`reclaimFailedForRetry`, kept being called on `repository` directly. The repository method carries
its own BARE `@Transactional` (REQUIRED propagation), so when `executeOnce` is invoked from inside a
caller's own `@Transactional` method -- exactly the shape of
`WalletService#requestCreatorWithdrawal`, and `creator-wallet.tsx` deliberately reuses the withdraw
idempotency key across retries -- the reclaim UPDATE JOINS the ambient transaction and holds the
row's write lock for that transaction's entire remaining lifetime. `markCompleted`/`markFailed` then
update the SAME row from an independent REQUIRES_NEW transaction on a second connection: a
deterministic deadlock. H2 surfaces it as PessimisticLockingFailureException after the lock-wait
timeout; MySQL's default 50s innodb_lock_wait_timeout would pin an HTTP thread and a pooled
connection that long. Net effect on the money path: a creator whose withdrawal fails once can never
complete it with that key.

MISSED BY (the specification for this gate). "An ambient-transaction x FAILED-row test case in
IdempotencyServicePersistenceTest -- the existing FAILED-reclaim test runs under NOT_SUPPORTED, so
there is no ambient transaction to contend with." The whole test class is annotated
`@Transactional(propagation = NOT_SUPPORTED)`, which means every test in it runs with NO ambient
transaction. That is precisely the one condition under which this defect cannot fire, so a suite
full of FAILED-reclaim tests proved nothing about it.

WHAT THIS GATE CHECKS, in order:

  A. STRUCTURE (comment-stripped -- see F-0266). Inside IdempotencyService, the reclaim in BOTH
     not-reserved branches must be `reservationOps.reclaimFailedForRetry(`, and NO mutating
     repository call may appear anywhere in that class. Plain reads (findByIdempotencyKey) are
     allowed on `repository`: a non-locking SELECT has nothing to deadlock against.
  B. That IdempotencyReservationOps.reclaimFailedForRetry is itself annotated
     REQUIRES_NEW -- routing through the bean is worthless if the boundary is not there.
  C. COVERAGE. IdempotencyServicePersistenceTest must contain a test method that actually builds
     the missing case: a row driven to FAILED, an ambient transaction opened explicitly
     (TransactionTemplate over the real PlatformTransactionManager, since the class-level
     NOT_SUPPORTED suppresses the framework's own), and `service.executeOnce` called INSIDE that
     transaction. Anchored to the method body, not a bare file-wide substring -- a sibling method
     mentioning executeOnce elsewhere must not green this.
  D. BEHAVIOUR. That test method is executed. Structure checks describe the fix; only running the
     test proves the deadlock does not occur.

WHY C/D ARE NOT REDUNDANT WITH A/B. A/B are text. They would green a tree where the ops bean was
correctly wired and the regression test had been deleted -- which is the exact state this record
says the codebase was in, and the reason the defect shipped.

Exit: 0 proved -- structure holds and the ambient-tx x FAILED-row test passes
      1 broken -- structure violated, the test case is missing/does not build the case, or it fails
      2 unavailable -- sources unreadable, mvn absent, the test module does not compile, or the
        run exceeded its wall-clock budget. Never exit 1 for any of those.

Usage: python .proof-os/gates/F-0162-reclaim-through-ops-bean.py
Env:   PROOF_F0162_TIMEOUT   seconds for the maven run (default 900)
       PROOF_F0162_SKIP_MVN  set to 1 to run A-C only (reports 2, never 0)
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
API = ROOT / "influora-api"
SERVICE = API / "src/main/java/com/influora/service/IdempotencyService.java"
OPS = API / "src/main/java/com/influora/service/IdempotencyReservationOps.java"
TEST = API / "src/test/java/com/influora/service/IdempotencyServicePersistenceTest.java"

TIMEOUT = int(os.environ.get("PROOF_F0162_TIMEOUT", "900"))

# repository calls that take a row-level write lock. findByIdempotencyKey and other reads are
# deliberately NOT here -- the fix's own contract keeps plain SELECTs on `repository`.
MUTATING_REPO_CALLS = (
    "reclaimFailedForRetry",
    "reclaimStaleInProgress",
    "markCompleted",
    "markFailed",
    "save",
    "saveAndFlush",
    "delete",
    "deleteById",
    "deleteAll",
)


def unavailable(msg: str, not_checked: str) -> None:
    print(f"  {msg}")
    print("VERDICT: unavailable -- the gate could not observe the invariant; this is NOT a finding")
    print(f"NOT CHECKED: {not_checked}")
    sys.exit(2)


def strip_java_comments(src: str) -> str:
    """Blank out // and /* */ comments, preserving line count and string literals.

    F-0266: a gate that greps file bytes fails the very fix whose comment quotes the forbidden
    string. IdempotencyService's javadoc quotes `repository.reclaimFailedForRetry(...)` verbatim
    while describing why it no longer does that, so raw-byte matching here would be guaranteed
    wrong in both directions.
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':  # string literal -- copy through, honouring backslash escapes
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\" and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\" and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        if src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append("\n" * src.count("\n", i, j))  # keep line numbers honest
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read(path: Path, label: str) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        unavailable(
            f"cannot read {label} ({path}): {exc}",
            "everything -- the source under test was not readable",
        )
        raise  # unreachable


def method_bodies(code: str) -> dict[str, str]:
    """Crude but adequate Java method-body extractor over comment-stripped source."""
    bodies: dict[str, str] = {}
    for m in re.finditer(r"\b(?:void|int|boolean|String|<[^>]+>\s*\w+)\s+(\w+)\s*\([^;{]*\)\s*\{", code):
        name = m.group(1)
        depth, i, n = 0, m.end() - 1, len(code)
        while i < n:
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        bodies[name] = code[m.end() : i]
    return bodies


def main() -> int:
    findings: list[str] = []

    for path, label in ((SERVICE, "IdempotencyService"), (OPS, "IdempotencyReservationOps"), (TEST, "IdempotencyServicePersistenceTest")):
        if not path.is_file():
            unavailable(
                f"{label} not found at {path.relative_to(ROOT)}",
                "everything -- a file the record names is absent, so the invariant is unlocatable",
            )

    service = strip_java_comments(read(SERVICE, "IdempotencyService"))
    ops = strip_java_comments(read(OPS, "IdempotencyReservationOps"))
    test_src = strip_java_comments(read(TEST, "IdempotencyServicePersistenceTest"))

    # ---- A. every write from IdempotencyService goes through the ops bean -------------------
    print("* A structure: IdempotencyService must not write the idempotency row itself")
    for call in MUTATING_REPO_CALLS:
        for m in re.finditer(r"\brepository\s*\.\s*" + call + r"\s*\(", service):
            line = service.count("\n", 0, m.start()) + 1
            findings.append(
                f"IdempotencyService.java:{line} calls repository.{call}( directly -- that carries the "
                f"repository's own bare @Transactional (REQUIRED), so it JOINS a caller's ambient "
                f"transaction and holds the row's write lock for its whole lifetime"
            )
    reclaims = re.findall(r"\breservationOps\s*\.\s*reclaimFailedForRetry\s*\(", service)
    branches = len(re.findall(r"\breservationOps\s*\.\s*tryReserve\s*\(", service))
    print(f"  reservationOps.reclaimFailedForRetry call sites: {len(reclaims)} (reservation branches: {branches})")
    if branches and len(reclaims) < branches:
        findings.append(
            f"only {len(reclaims)} of {branches} not-reserved branches reclaim via reservationOps -- "
            f"executeOnce and runExclusive both take the FAILED-retry path and both must"
        )
    if not reclaims:
        findings.append("IdempotencyService never reclaims via reservationOps at all")

    # ---- B. the bean's reclaim actually opens its own transaction --------------------------
    print("* B structure: IdempotencyReservationOps.reclaimFailedForRetry must be REQUIRES_NEW")
    sig = re.search(r"public\s+int\s+reclaimFailedForRetry\s*\(", ops)
    if not sig:
        findings.append("IdempotencyReservationOps has no public reclaimFailedForRetry(...) -- the "
                        "reclaim has no REQUIRES_NEW home to be routed to")
    else:
        # Only THIS method's own annotation block counts. A fixed-width lookback would reach the
        # PREVIOUS method's @Transactional(REQUIRES_NEW) and green a reclaim that had lost its own
        # -- caught by the falsification harness, so the window is cut at the prior member's
        # closing brace / semicolon instead.
        head = ops[: sig.start()]
        cut = max(head.rfind("}"), head.rfind(";"))
        preamble = head[cut + 1 :] if cut != -1 else head
        if not re.search(r"@Transactional\s*\(\s*propagation\s*=\s*(?:Propagation\.)?REQUIRES_NEW\s*\)", preamble):
            findings.append(
                "IdempotencyReservationOps.reclaimFailedForRetry is not annotated "
                "@Transactional(propagation = REQUIRES_NEW) -- routing through the bean buys nothing "
                "without the independent transaction boundary"
            )

    # ---- C. the missing test case exists and genuinely builds the case ---------------------
    print("* C coverage: an ambient-transaction  x  FAILED-row case in IdempotencyServicePersistenceTest")
    class_level_not_supported = bool(
        re.search(r"@Transactional\s*\(\s*propagation\s*=\s*(?:Propagation\.)?NOT_SUPPORTED\s*\)\s*(?:final\s+)?class\b", test_src)
    )
    if class_level_not_supported:
        print("  class runs @Transactional(NOT_SUPPORTED) -- an ambient tx must be opened explicitly")

    target = None
    for name, body in method_bodies(test_src).items():
        if "executeOnce" not in body:
            continue
        sets_failed = "Status.FAILED" in body
        opens_tx = "TransactionTemplate" in body or "transactionManager" in body
        if not (sets_failed and opens_tx):
            continue
        exec_at = min(
            [body.find(t) for t in ("txTemplate.execute", ".execute(", ".executeWithoutResult(") if body.find(t) != -1]
            or [-1]
        )
        call_at = body.find("executeOnce")
        if exec_at != -1 and exec_at < call_at:
            target = name
            break

    if target is None:
        findings.append(
            "no test method in IdempotencyServicePersistenceTest drives a row to FAILED, opens its "
            "own ambient transaction, and calls executeOnce INSIDE it -- the class-level "
            "NOT_SUPPORTED means every other FAILED-reclaim test runs under the one condition where "
            "this deadlock cannot fire"
        )
    else:
        print(f"  found: {target}()")

    if findings:
        for f in findings:
            print(f"  x {f}")
        print("VERDICT: broken -- F-0162 is present: the FAILED-row reclaim can join a caller's "
              "ambient transaction and deadlock the withdrawal-retry path, and/or nothing tests it")
        print("NOT CHECKED: behaviour -- the regression test was not executed, because the structure "
              "or the test case itself is already wrong. Also unchecked: every OTHER caller of "
              "executeOnce, MySQL's real lock-wait behaviour (the test runs H2), and whether "
              "creator-wallet.tsx still reuses the withdraw key across retries.")
        return 1

    # ---- D. run it -------------------------------------------------------------------------
    if os.environ.get("PROOF_F0162_SKIP_MVN") == "1":
        unavailable(
            "PROOF_F0162_SKIP_MVN=1 -- behaviour deliberately not executed",
            "behaviour -- structure A/B/C hold, but the deadlock itself was not exercised",
        )

    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    report = API / "target/surefire-reports" / "com.influora.service.IdempotencyServicePersistenceTest.txt"
    try:
        report.unlink()  # never read a previous run's result as this run's evidence
    except OSError:
        pass
    print(f"* D behaviour: mvn -o test -Dtest=IdempotencyServicePersistenceTest#{target} (budget {TIMEOUT}s)")
    try:
        proc = subprocess.run(
            [mvn, "-o", "-B", "test", f"-Dtest=IdempotencyServicePersistenceTest#{target}",
             "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false"],
            cwd=API, capture_output=True, text=True, timeout=TIMEOUT, shell=(os.name == "nt"),
        )
    except FileNotFoundError:
        unavailable("mvn is not on PATH", "behaviour -- structure A/B/C hold, but nothing was run")
        return 2
    except subprocess.TimeoutExpired:
        unavailable(
            f"the maven run exceeded {TIMEOUT}s",
            "behaviour -- structure A/B/C hold, but the test did not finish",
        )
        return 2

    out = (proc.stdout or "") + (proc.stderr or "")

    if "COMPILATION ERROR" in out or "Compilation failure" in out:
        offenders = sorted({
            m.group(1) for m in re.finditer(r"([A-Za-z0-9_]+\.java):\[\d+", out)
        })
        print(f"  the module does not compile ({', '.join(offenders[:6]) or 'unknown source'})")
        unavailable(
            "sources do not compile, so no test could run",
            "behaviour -- structure A/B/C hold, but the compile break (unrelated to this record) "
            "means the ambient-tx regression test is currently DEAD COVERAGE: it protects nothing "
            "until the module builds again. Fix the compile break, then re-run this gate.",
        )
        return 2

    # Read surefire's OWN report for this class, not the aggregate console line. A build that
    # fails for any reason before/after surefire, or a -Dtest selector that matched nothing, both
    # print "Tests run: 0" and a non-zero exit -- scoring either as a finding would be a false red.
    ran = failures = errors = None
    if report.is_file():
        try:
            text = report.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)", text)
        if m:
            ran, failures, errors = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
            print(f"  surefire report: Tests run {ran}, Failures {failures}, Errors {errors}")

    if not ran:
        console = [ln.strip() for ln in out.splitlines() if re.search(r"Tests run: \d+, Failures:", ln)]
        if console:
            print(f"  console: {console[-1]}")
        print(f"  {target} did not execute")
        unavailable(
            "the target test never ran (no surefire report, or zero tests selected)",
            "behaviour -- structure A/B/C hold, but the ambient-tx  x  FAILED-row test did not "
            "execute, so it is currently DEAD COVERAGE: it protects nothing until the module "
            "builds and surefire selects it. Re-run this gate once the build is green.",
        )
        return 2

    if failures or errors:
        for ln in out.splitlines():
            if target in ln and ("ERROR" in ln or "FAIL" in ln):
                print(f"  {ln.strip()[:300]}")
        for marker in ("PessimisticLockingFailureException", "UnexpectedRollbackException",
                       "CannotAcquireLockException", "Timeout trying to lock table", "Deadlock"):
            if marker in out:
                print(f"  -> saw {marker} -- the deadlock this record describes")
        print("VERDICT: broken -- the ambient-transaction  x  FAILED-row retry does not complete "
              "cleanly; a creator retrying a failed withdrawal with the same key deadlocks")
        print("NOT CHECKED: nothing further -- the behavioural test itself failed, which is the "
              "finding. Structure A/B/C did hold, so look for a NEW path into the row rather than "
              "a reverted call site.")
        return 1

    print("VERDICT: aligned (proved) -- every idempotency-row write goes through "
          "IdempotencyReservationOps' REQUIRES_NEW boundary, and executeOnce retrying a FAILED key "
          "from inside a live ambient transaction completes without deadlocking")
    print("NOT CHECKED: MySQL's real innodb_lock_wait_timeout behaviour (this runs H2 in MySQL "
          "mode); the OTHER ~dozen executeOnce callers, only the WalletService-shaped one is "
          "exercised; concurrency between two ambient-transaction retries of the same key at once; "
          "and whether the caller ABOVE executeOnce holds any other lock on the same row.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
