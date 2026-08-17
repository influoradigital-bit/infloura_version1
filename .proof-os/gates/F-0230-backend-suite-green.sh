#!/usr/bin/env bash
# F-0230-backend-suite-green.sh — gate for F-0230 (pre-existing-red-test).
#
# The backend suite was RED at HEAD and nothing noticed. `gates/build.mvn.sh` runs with
# -DskipTests, so every gate run reported "aligned (proved) — the module compiles" over three
# failing tests. Those three were not flaky infrastructure: PayoutRepositoryCreatorScopingTest
# was correctly detecting a live cross-tenant payout leak (F-0234) from the day it landed, and
# the red was read as noise for as long as no gate executed it.
#
# That is the class this gate closes: not "those three tests", but "a red backend suite is
# invisible to every gate". It runs the tests build.mvn.sh skips.
#
# It is SLOW (~4 minutes, 1789 tests) and that is the cost of the guarantee — build.mvn.sh stays
# fast for the compile-only question, and this answers the different one.
#   exit 0 = proved (suite green) · 1 = broken (any failure or error) · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· no influora-api/pom.xml here — unavailable"; exit 2; }

BUDGET="${PROOF_BACKEND_SUITE_TIMEOUT:-1500}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 30 $BUDGET"; else TO=""; fi

echo "· mvn -o test (full backend suite, offline; budget ${BUDGET}s)"
out=$(cd influora-api && $TO mvn -o test 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything — the suite did not finish, so no test result was observed"
  exit 2
fi

summary=$(printf '%s\n' "$out" | grep -E "Tests run: [0-9]+, Failures:" | tail -1)
if [ -z "$summary" ]; then
  printf '%s\n' "$out" | tail -20
  echo "  no test summary in the output — the suite did not run to a result"
  exit 2
fi
echo "  $summary"

if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "^\[ERROR\]   [A-Za-z]" | head -20
  echo "VERDICT: broken — the backend suite is red. Read the failures before assuming they are"
  echo "         flaky: F-0234 was a real cross-tenant leak that spent its whole life being"
  echo "         dismissed as broken test infrastructure."
  exit 1
fi

echo "VERDICT: aligned (proved) — the backend suite is green"
echo "NOT CHECKED: whether the tests ASSERT anything worth asserting — a green suite of weak tests"
echo "             passes this gate exactly as a strong one does; coverage; anything Testcontainers"
echo "             skips when Docker is down; and the 10 tests the suite itself marks Skipped."
exit 0
