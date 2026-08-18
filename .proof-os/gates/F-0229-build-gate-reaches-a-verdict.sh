#!/usr/bin/env bash
# F-0229-build-gate-reaches-a-verdict.sh — gate for F-0229 (gate-budget-false-unavailable).
#
# THE RECORD AS FILED DOES NOT REPRODUCE, and its `where` names a file this project does
# not have. Measured 2026-08-18 (.proof-os/tasks/T-BRANDOPEN-0817/F-0229.prefix.log):
# solo `npm test` takes 99s against a 300s budget and the whole three-leg gates/build.sh
# takes 159s. Nothing ever timed out. The 900s test budget a previous edit installed
# cited a 476s solo run and an evidence log that did not exist; that number does not
# reproduce and the justification was withdrawn.
#
# What DID reproduce, on every run: gates/build.sh printed "tests could not run —
# unavailable" and exited 2 while the suite had in fact run to completion and FAILED.
# Its env_issue() carried the bare token `network` and was matched against the WHOLE
# test output; two suites deliberately log `Error: network down`. So a RED SUITE WAS
# LAUNDERED INTO `unavailable`, in both directions, every run. That is why this gate had
# never returned a verdict — not a budget that was too small, a discriminator that could
# not tell "the runner could not start" from "a test printed the word network".
#
# This gate asserts that build.sh can reach a real verdict, and that it stays honest
# when it cannot. The honesty direction matters more than the verdict direction: turning
# a timeout into a pass is the worst available outcome for this record, so it is
# asserted directly, with a starved budget, against a subject that would otherwise fail.
#   exit 0 = proved · 1 = broken · 2 = unavailable
# Usage: gates/F-0229-build-gate-reaches-a-verdict.sh [project_dir]
set -u

NC="NOT CHECKED: the real suite — this gate never runs this project's vitest (that is
             gates/build.sh's own job, ~200s); it proves the DISCRIMINATOR and the rc
             contract against synthetic runners instead, so a change to the real test
             command is invisible here. Also not checked: that the chosen budgets are
             right for a slower machine (only that they are finite and enforced); the
             tsc and production-build legs, which are exercised only as 'skipped' on the
             fixtures; whether the advisory npm-test lock is honoured by agents that
             never source gates/_lock.sh — it cannot be, and a run started outside the
             lock still contends."
say_nc() { printf '%s\n' "$NC"; }
broken() { echo "VERDICT: broken — $1 (F-0229)"; say_nc; exit 1; }
unavail() { echo "· $1 — unavailable"; say_nc; exit 2; }

ROOT="${1:-}"
if [ -z "$ROOT" ]; then
  ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || unavail "cannot resolve project root"
fi
[ -d "$ROOT" ] || unavail "not a directory: $ROOT"
GATES="$ROOT/.proof-os/gates"
BUILD="$GATES/build.sh"
[ -f "$BUILD" ] || unavail "$BUILD missing"
command -v node >/dev/null 2>&1 || unavail "node not on PATH"
command -v npm  >/dev/null 2>&1 || unavail "npm not on PATH"

TMP=$(mktemp -d 2>/dev/null) || unavail "cannot create a temp dir"
cleanup() { rm -rf "$TMP" 2>/dev/null; }
trap cleanup EXIT

# ── leg 1 · `network` is gone from env_issue ─────────────────────────────────
# Read build.sh's CODE, not its bytes: this file's own header quotes `network` a dozen
# times explaining the defect, and so does build.sh's. Grepping raw would fail the fix
# because the fix documents itself — F-0266, one layer up. gates/_code.sh is how every
# other gate in this tree avoids that, so this one uses it too.
if [ -f "$GATES/_code.sh" ]; then
  SELF="$GATES"
  # shellcheck source=/dev/null
  . "$GATES/_code.sh" 2>/dev/null || unavail "gates/_code.sh could not be sourced"
  code_ready || unavail "$(code_why)"
  BV=$(code_view "$BUILD" shell) || unavail "$(code_why)"
else
  unavail "gates/_code.sh missing; cannot read build.sh's code without also reading the comments that explain this very defect"
fi
if grep -qE "\|network\||'network'|\"network\"|\|network\b" "$BV"; then
  echo "· env_issue still carries the bare token 'network':"
  grep -n "network" "$BV" | head -4
  broken "the environment heuristic still matches the word 'network' in test output; any suite that asserts on a failed fetch will be reported as 'the runner could not run'"
fi
grep -q "ran_to_completion" "$BV" || \
  broken "build.sh has no results-summary discriminator; nothing separates 'the runner never started' from 'the runner reported failures'"
echo "· env_issue no longer matches the bare token 'network'; a results-summary discriminator exists"

# ── fixture harness ──────────────────────────────────────────────────────────
# A minimal project build.sh will accept: package.json + a node_modules directory. No
# tsconfig.json and no build script, so those two legs report unavailable and only the
# test leg is under test. Each fixture differs ONLY in what its test runner prints.
mkfixture() {  # mkfixture <dir> <runner-js>
  mkdir -p "$1/node_modules" "$1/.proof-os"
  cat > "$1/package.json" <<'PKG'
{ "name": "f0229-fixture", "version": "1.0.0", "private": true,
  "scripts": { "test": "node runner.js" } }
PKG
  printf '%s\n' "$2" > "$1/runner.js"
}

# A. the runner RAN and FAILED, and its output contains the old trigger word.
mkfixture "$TMP/reported" '
console.log("stderr | some.test.tsx > Failed to refresh deal Error: network down");
console.log(" Test Files  1 failed | 106 passed (107)");
console.log("      Tests  2 failed | 666 passed (668)");
process.exit(1);'
# B. the runner NEVER STARTED — no summary anywhere.
mkfixture "$TMP/absent" '
console.error("sh: 1: vitest: not found");
process.exit(127);'
# C. the runner hangs. Used with a starved budget to prove the honesty direction.
mkfixture "$TMP/slow" '
setTimeout(function () {
  console.log(" Test Files  1 passed (1)");
  console.log("      Tests  4 passed (4)");
}, 30000);'

# `VAR=1 cmd` only works when the assignment is LITERAL at parse time — expanding "$2"
# into that position makes the shell treat `PROOF_GATE_TIMEOUT=1` as the command name
# and return 127, which this gate then read as a failed timeout assertion. `env` takes
# the assignments as ordinary arguments, after expansion, which is the whole point of it.
run_fixture() {  # run_fixture <dir> [VAR=val ...] -> sets RC and OUT
  fdir="$1"; shift
  OUT=$(cd "$fdir" && env PROOF_OS_DIR="$fdir/.proof-os" "$@" bash "$BUILD" "$fdir" 2>&1); RC=$?
}

# ── leg 2 · a runner that reported is a FINDING, not unavailable ─────────────
run_fixture "$TMP/reported"
if [ "$RC" -eq 2 ]; then
  printf '%s\n' "$OUT" | sed -n '1,12p'
  broken "a test suite that ran to completion and FAILED was reported as 'unavailable' (exit 2); a red suite is being laundered into 'the tool could not run' — this is the defect itself"
fi
[ "$RC" -eq 1 ] || broken "a failing test suite produced exit $RC, expected 1 (a real finding)"
printf '%s\n' "$OUT" | grep -q "LEG tests: BROKEN" || \
  broken "the per-leg table does not report the test leg as BROKEN for a suite that failed"
echo "· a suite that reported and failed -> exit 1 (finding), despite 'network down' in its output"

# ── leg 3 · a runner that never started IS unavailable ───────────────────────
run_fixture "$TMP/absent"
[ "$RC" -eq 2 ] || broken "a test runner that was never installed produced exit $RC, expected 2; an uninstalled runner is not a defect in the code"
printf '%s\n' "$OUT" | grep -q "LEG tests: unavailable" || \
  broken "the per-leg table does not report the test leg as unavailable when the runner never started"
echo "· a runner that never started -> exit 2 (unavailable), NOT a finding"

# ── leg 4 · the per-leg table names every leg ────────────────────────────────
# One unavailable leg used to erase the fact that the others proved: the summary said
# "some oracles could not run" and named none of them.
for l in "LEG tsc:" "LEG build:" "LEG tests:"; do
  printf '%s\n' "$OUT" | grep -q "$l" || broken "the verdict summary does not name '$l'; a partial result again hides which leg was actually checked"
done
echo "· the verdict names each leg separately (tsc / build / tests)"

# ── leg 5 · THE HONESTY DIRECTION: a starved budget is never a pass ──────────
# The subject here would otherwise pass, so a 0 could only come from the timeout being
# swallowed. Converting a timeout into a pass is the worst outcome available for this
# record; it is asserted here rather than assumed.
run_fixture "$TMP/slow" "PROOF_GATE_TIMEOUT=1" "PROOF_GATE_TEST_TIMEOUT=3"
# ASSERT THE LEG, NOT JUST THE EXIT CODE. On these fixtures tsc and build are always
# unavailable (no tsconfig.json, no build script), so the overall exit is 2 whatever the
# test leg decides — an earlier draft of this gate checked only the exit and therefore
# passed a build.sh that had been edited to record a timed-out test leg as `proved`.
# The wrong fix was invisible behind two honest unavailables. The leg table is where the
# inversion actually shows.
if printf '%s\n' "$OUT" | grep -q "LEG tests: proved"; then
  printf '%s\n' "$OUT" | sed -n '1,14p'
  broken "a test leg killed by its own budget was recorded as PROVED; a timeout has been converted into a pass, which inverts the whole point of an unavailable verdict"
fi
printf '%s\n' "$OUT" | grep -q "LEG tests: unavailable" || \
  broken "a timed-out test leg is not reported as unavailable in the leg table"
[ "$RC" -eq 0 ] && broken "the whole gate returned PROVED (exit 0) with a test leg that never finished"
[ "$RC" -eq 2 ] || broken "a timed-out test leg produced exit $RC, expected 2 (unavailable)"
printf '%s\n' "$OUT" | grep -qE "exceeded .*budget|exceeded [0-9]+s" || \
  broken "a timed-out leg does not say it exceeded its budget; an unavailable with no cause is a belief with a filename"
echo "· a starved budget -> LEG tests: unavailable and exit 2, never proved, and it names the budget it blew"

# ── leg 6 · the rc/liveness contract ─────────────────────────────────────────
# The pre-fix project build.sh sourced nothing and wrote no rc file at all, so
# gates/liveness.py could not tell whether it had run, hung, or died.
# _rc.sh ships with the PLUGIN, not with a project's gates dir. If it is not reachable
# from the gates dir under test, build.sh cannot write an rc file no matter how
# correctly it is wired — that is this gate being unable to check, not the subject being
# broken, and calling it broken made every other leg unreachable behind it.
RCLIB=""
for c in "$GATES/_rc.sh" "$GATES"/../.runtime/*/gates/_rc.sh; do
  [ -f "$c" ] && { RCLIB="$c"; break; }
done
RCF="$TMP/reported/.proof-os/rc/build.rc"
if [ -z "$RCLIB" ]; then
  echo "· gates/_rc.sh is not reachable from $GATES — the rc contract cannot be checked here"
  NC="$NC
             ALSO NOT CHECKED: the rc/liveness contract — gates/_rc.sh was not reachable
             from the gates directory under test, so whether build.sh records its own
             exit was not established."
  echo "VERDICT: aligned (proved) — build.sh reaches a real verdict on a suite that reports, stays unavailable when the runner never starts or the budget runs out, and names each leg"
  say_nc
  exit 0
fi
[ -f "$RCF" ] || broken "gates/build.sh wrote no rc file; gates/liveness.py cannot tell a finished run from a process that died mid-verdict (F-0026)"
grep -q "observed=" "$RCF" || broken "the rc record has no observed= field; liveness treats a code nobody watched as believed, not proved"
grep -q "pid=" "$RCF" || broken "the rc record names no pid; a code with no process is a belief with a filename"
grep -qE "exit=[0-9]+" "$RCF" || broken "the rc record carries no exit code"
echo "· rc contract honoured: $(cat "$RCF")"

echo "VERDICT: aligned (proved) — build.sh reaches a real verdict on a suite that reports, stays unavailable when the runner never starts or the budget runs out, names each leg, and records its own liveness"
say_nc
exit 0
