#!/usr/bin/env bash
# gates/build.sh — meera's oracle. Exit codes, not opinion.
# origin: registry grants meera may_claim=proved; PROOFOS.md roadmap item 2.
#         An oracle with no gate is the silent-oracle trap (F-0023).
# LAW (false-red, F-0013/F-0015/F-0017): tool-cannot-run => exit 2 (unavailable/believed).
#      exit 1 is ONLY for real findings. exit 0 is proved.
# LAW (F-0025, unbounded-gate-runtime): every step is wall-clock bounded. A step that
#      exceeds its budget is UNAVAILABLE (exit 2) — never a hang, never a false red.
#      Override with PROOF_GATE_TIMEOUT (seconds per step, default 300).
# LAW (F-0026 / law 7): writes an rc file into the PROJECT's .proof-os — $SELF resolved
#      before the cd, rc_init after it — so gates/liveness.py can tell "still running"
#      from "died without a verdict".
# Usage: gates/build.sh [project_dir] [health_url]
#
# ─────────────────────────────────────────────────────────────────────────────
# F-0229 (gate-budget-false-unavailable). MEASURED, 2026-08-18, this machine, this
# tree — see .proof-os/tasks/T-BRANDOPEN-0817/F-0229.prefix.log for the raw runs.
#
# The record said the vitest leg blows the 300s budget under concurrent load. It does
# not. Solo `npm test` here finishes in 99s wall clock (vitest's own Duration 95.56s;
# 668 tests / 107 files), and the whole three-leg gate finishes in 159s. Nothing timed
# out. A previous edit of this file raised the test budget to 900s citing a "~476s
# solo run" and an evidence log that was never written; that number does not reproduce
# and the justification is withdrawn.
#
# What DOES reproduce, every single run, is the gate reporting
#
#     · npm test
#       tests could not run — unavailable
#     VERDICT: partial — some oracles could not run (believed, not proved)
#
# with exit 2 while the suite had in fact run to completion and FAILED (2 tests in
# src/components/brand/deal-room/__tests__/demo-sign-flow-alive.test.tsx). The cause is
# `env_issue`, which was matched against the ENTIRE test output and carried the bare
# token `network`. Two suites deliberately exercise a failed fetch and log
# `Error: network down`. So the word "network" appearing anywhere in 4,000 lines of
# test output was read as "the test runner could not be executed".
#
# That is worse than the budget story it was filed as. It does not merely lose a green:
# it launders a RED SUITE INTO `unavailable`, in both directions, on every run — which
# is precisely why this gate "has never returned a verdict". It never could.
#
# The discriminator now used instead: A TEST RUNNER THAT PRINTED A RESULTS SUMMARY RAN.
# If the output carries vitest/jest's own `Test Files` / `Tests ` / `Suites:` tally,
# the runner started, loaded the suite and reported — so a non-zero exit is a finding
# about the CODE, and no environment heuristic may overrule it. `env_issue` is only
# consulted when there is no summary at all, i.e. when nothing ran. `network` is gone
# from the pattern even there: npm's own registry failures already match
# `registry.npmjs`/`ETIMEDOUT`/`ENOTFOUND`, and the bare word never distinguished a
# broken store from a test asserting on one.
#
# Contention is still real, so the expensive leg is SERIALISED rather than given a
# bigger number (gates/_lock.sh): two agents running vitest at once now queue instead
# of starving each other, and each gets its honest solo time. The lock wait has its own
# separate ceiling and is NOT charged against the leg's budget. If the lock cannot be
# taken in time, that is `unavailable` — never a pass.
# ─────────────────────────────────────────────────────────────────────────────
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" || { echo "· project dir unreadable — unavailable"; exit 2; }
URL="${2:-}"

# rc/liveness (F-0026). _rc.sh ships with the plugin, not with a project's own gates
# dir, so look in both places and no-op if neither has it: a missing liveness helper
# must not change this gate's verdict.
for _rc_cand in "$SELF/_rc.sh" "$SELF"/../.runtime/proof-os/gates/_rc.sh; do
  [ -f "$_rc_cand" ] && { . "$_rc_cand" 2>/dev/null || true; break; }
done
if type rc_init >/dev/null 2>&1; then rc_init build; fi

. "$SELF/_lock.sh" 2>/dev/null || true

fail=0; unavail=0; ran=0
TMO="${PROOF_GATE_TIMEOUT:-300}"
# The test leg gets its own ceiling. Solo is 99s (measured above); 600s is ~6x that,
# which covers a cold cache and a loaded machine while still being FINITE and still
# wrapped in `timeout`, so a genuine hang is unavailable (exit 2), never proved.
# It is deliberately not the 900s a previous edit set on withdrawn evidence.
TMO_TEST="${PROOF_GATE_TEST_TIMEOUT:-600}"
# How long we are willing to QUEUE behind another agent's vitest before giving up and
# saying so. Waiting is not running; this is not part of TMO_TEST.
LOCK_WAIT="${PROOF_GATE_LOCK_WAIT:-900}"
if command -v timeout >/dev/null 2>&1; then
  TO="timeout -k 10 $TMO"; TO_TEST="timeout -k 10 $TMO_TEST"
else
  TO=""; TO_TEST=""
fi
timed_out() { [ "$1" -eq 124 ] || [ "$1" -eq 137 ]; }

# Per-leg verdicts. A single unavailable leg used to erase the fact that the other two
# PROVED — the summary said "some oracles could not run" and named none of them. Each
# leg records its own outcome and the reason, and every exit path prints the table.
LEGS=""
leg() { LEGS="${LEGS}  LEG ${1}: ${2}
"; }
summary() { printf '%s' "$LEGS"; }

# MISSING BINARY. npm runs lifecycle scripts through `sh` (dash), whose wording is
# `sh: 1: vitest: not found` — the bash-only `command not found` never matched it, so an
# UNINSTALLED test runner was reported as broken CODE at exit 1. Found by this record's
# own gate (F-0229-build-gate-reaches-a-verdict.sh, leg 3) against a fixture whose runner
# was absent; the pattern is the one gates/build.node.sh already carried and this file
# never did. Broadening it cannot re-open the laundering defect above, because env_issue
# is only consulted when the runner produced NO results summary at all.
missing_binary() {
  printf '%s\n' "$1" | grep -qE \
    '(^|[^[:alnum:]_])(/usr)?(/bin/)?(sh|ash|dash|bash|zsh|env)(\.exe)?:( *[0-9]+:)? .*:? (command )?not found|command not found:|(^|[[:space:]])spawn( sync)? .* ENOENT'
}
# Consulted ONLY when a runner produced no results at all (see the header). `network`
# is deliberately absent: it matched test output, not the environment.
env_issue() {
  missing_binary "$1" && return 0
  printf '%s\n' "$1" | grep -qiE 'ENOENT|ENOTFOUND|Cannot find module|Cannot find package|ERR_MODULE_NOT_FOUND|command not found|not recognized|is not recognized as|EACCES|ENOSPC|ETIMEDOUT|registry\.npmjs'
}
# The runner's own tally. Its presence proves the suite was collected and reported.
ran_to_completion() {
  printf '%s\n' "$1" | grep -qE '(^|[^[:alnum:]])(Test Files|Tests|Suites|Test Suites)[[:space:]]+[0-9]|[0-9]+ (passed|failed)'
}

if [ ! -f package.json ]; then
  echo "· no package.json here — build oracle cannot run (unavailable)"
  leg all "unavailable (no package.json)"; summary; exit 2
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "· npm UNAVAILABLE"; leg all "unavailable (npm not on PATH)"; summary; exit 2
fi
if [ ! -d node_modules ]; then
  echo "· node_modules absent — build/test cannot run without install (unavailable, NOT a finding)"; unavail=1
fi

has_script() { node -e "process.exit(require('./package.json').scripts?.['$1']?0:1)" 2>/dev/null; }

# ---- 1. typecheck -----------------------------------------------------------
if [ -f tsconfig.json ] && [ -d node_modules ]; then
  echo "· tsc --noEmit (budget ${TMO}s)"; ran=1
  out=$($TO npx --no-install tsc --noEmit 2>&1); rc=$?
  if timed_out $rc; then
    echo "  tsc exceeded ${TMO}s — unavailable (F-0025)"; unavail=1
    leg tsc "unavailable (exceeded its own ${TMO}s budget)"
  elif [ $rc -ne 0 ]; then
    if env_issue "$out"; then echo "  tsc unavailable"; unavail=1; leg tsc "unavailable (environment)"
    else echo "$out" | head -12; fail=1; leg tsc "BROKEN (type errors above)"; fi
  else
    leg tsc "proved"
  fi
elif [ ! -f tsconfig.json ]; then
  echo "· tsc skipped (no tsconfig.json) — unavailable"; unavail=1
  leg tsc "unavailable (no tsconfig.json — nothing type-checked this tree)"
else
  leg tsc "unavailable (no node_modules)"
fi

# ---- 2. build ---------------------------------------------------------------
if has_script build && [ -d node_modules ]; then
  echo "· npm run build (budget ${TMO}s)"; ran=1
  out=$($TO npm run build 2>&1); rc=$?
  if timed_out $rc; then
    echo "  build exceeded ${TMO}s — unavailable (F-0025)"; unavail=1
    leg build "unavailable (exceeded its own ${TMO}s budget)"
  elif [ $rc -ne 0 ]; then
    if env_issue "$out"; then echo "  build could not run (env) — unavailable"; unavail=1
      leg build "unavailable (environment)"
    else echo "$out" | tail -20; fail=1; leg build "BROKEN (build failed)"; fi
  else
    leg build "proved"
  fi
else
  echo "· no build script or no node_modules — unavailable"; unavail=1
  leg build "unavailable (no build script or no node_modules)"
fi

# ---- 3. tests ---------------------------------------------------------------
if has_script test && [ -d node_modules ]; then
  # Serialise: one test runner per store at a time (F-0229). If locking itself is
  # unavailable we still run — that is exactly today's behaviour — but we say so,
  # because an unserialised run is the one that can be starved.
  locked=0
  if type gate_lock >/dev/null 2>&1; then
    gate_lock npm-test "$LOCK_WAIT"; lrc=$?
    case $lrc in
      0) locked=1; [ "${LOCK_WAITED:-0}" -gt 0 ] && echo "· queued ${LOCK_WAITED}s behind another test run" ;;
      1) echo "· $LOCK_WHY — the test leg never started; unavailable, NOT a finding"
         unavail=1; leg tests "unavailable (waited ${LOCK_WAITED}s for the npm-test lock and never got it)" ;;
      *) echo "· could not serialise the test leg (${LOCK_WHY:-no lock available}) — running unserialised" ;;
    esac
  fi
  if [ "${lrc:-0}" -ne 1 ]; then
    echo "· npm test (budget ${TMO_TEST}s)"; ran=1
    out=$($TO_TEST npm test --silent 2>&1); rc=$?
    [ $locked -eq 1 ] && gate_unlock npm-test
    if timed_out $rc; then
      echo "  tests exceeded ${TMO_TEST}s — unavailable (F-0025)"; unavail=1
      leg tests "unavailable (exceeded its own ${TMO_TEST}s budget)"
    elif [ $rc -eq 0 ]; then
      printf '%s\n' "$out" | grep -aE '(Test Files|Tests)[[:space:]]+[0-9]' | tail -2
      leg tests "proved"
    elif ran_to_completion "$out"; then
      # THE RUNNER REPORTED. Whatever it says is a finding about the code, and no
      # environment heuristic gets to overrule it — that inversion is F-0229 itself.
      printf '%s\n' "$out" | tail -20
      fail=1; leg tests "BROKEN (the suite ran and reported failures)"
    elif env_issue "$out" || printf '%s\n' "$out" | grep -qiE 'no test (files|specs) found'; then
      printf '%s\n' "$out" | tail -5
      echo "  tests could not run (no results summary, and the output names an environment problem) — unavailable, NOT a finding"
      unavail=1; leg tests "unavailable (the runner never produced a result summary)"
    else
      printf '%s\n' "$out" | tail -20
      fail=1; leg tests "BROKEN (non-zero exit)"
    fi
  fi
else
  echo "· no test script or no node_modules — unavailable"; unavail=1
  leg tests "unavailable (no test script or no node_modules)"
fi

# ---- 4. optional health check ----------------------------------------------
if [ -n "$URL" ]; then
  if command -v curl >/dev/null 2>&1; then
    echo "· curl $URL"; ran=1
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$URL" 2>/dev/null) || code="000"
    if [ "$code" = "000" ]; then echo "  unreachable — unavailable"; unavail=1; leg health "unavailable (unreachable)"
    elif [ "$code" -ge 400 ]; then echo "  HTTP $code"; fail=1; leg health "BROKEN (HTTP $code)"
    else echo "  HTTP $code"; leg health "proved (HTTP $code)"; fi
  else
    echo "· curl UNAVAILABLE"; unavail=1; leg health "unavailable (no curl)"
  fi
fi

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; summary; exit 1; }
[ $ran -eq 0 ] && { echo "VERDICT: partial — nothing actually ran (believed, not proved)"; summary; exit 2; }
[ $unavail -eq 1 ] && { echo "VERDICT: partial — some oracles could not run (believed, not proved)"; summary; exit 2; }
echo "VERDICT: aligned (proved)"; summary; exit 0
