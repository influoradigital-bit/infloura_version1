#!/usr/bin/env bash
# F-0893 [arjun · 2026-09-18] — a CANCELLED or HALTED ex-Pro workspace keeps a frozen
# currentPeriodEnd; the monthly reset must still refill it as Free (5 -> 100). e35d583's period
# guard silently skipped it forever. Runs the two regression tests from a git archive of HEAD
# (never the shared working tree / target/). Exit 0 proved · 1 broken · 2 unavailable.
set -u
ROOT="$(git rev-parse --show-toplevel)" || exit 2
command -v mvn >/dev/null || exit 2
TMP="$(mktemp -d)" || exit 2
trap 'rm -rf "$TMP"' EXIT
git -C "$ROOT" archive HEAD influora-api | tar -x -C "$TMP" || exit 2
cd "$TMP/influora-api" || exit 2
mvn -o -q clean test-compile >"$TMP/b.log" 2>&1 || exit 1
mvn -o surefire:test \
  -Dtest='AICreditServiceTest#testResetForNewCycleRefillsDespiteFrozenPeriodEndOnCancelledSubscription+testResetForNewCycleRefillsDespiteFrozenPeriodEndOnHaltedSubscription' \
  >"$TMP/t.log" 2>&1 || exit 1
grep -q "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0" "$TMP/t.log" || exit 1
exit 0
