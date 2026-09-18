#!/usr/bin/env bash
# gates/f0901-brand-metrics-consent.sh — closes F-0901 (H4 consent-gate-never-opens).
#
# MetricsAuthorizationService only accepted a workspace-scoped meta_oauth_tokens row, but a
# creator's own Meta connection is stored with workspace_id NULL, so no brand could ever read a
# marketplace creator's analytics. Rule now (ruling 2026-09-15-brand-preconsent-visibility, user
# 2026-09-18 "Creator has agreed"): the Meta pairing OR a collaboration the CREATOR agreed to
# (APPLICATION not CANCELLED, or TERMS_AGREED onward incl. DISPUTED). Brand-only INVITED /
# unanswered offers / CANCELLED are refused.
#
# Runs the tests rather than grepping them. Falsified 2026-09-18:
#   - allowing CANCELLED                        -> 1 failure  (MetricsAuthorizationServiceTest)
#   - allowing any non-INVITED invitation       -> 5 failures (MetricsAuthorizationServiceTest)
#   - dropping the workspace subquery from JPQL -> 1 failure  (CollaborationRepositoryWorkspaceCreatorQueryTest)
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

SVC=influora-api/src/main/java/com/influora/service/MetricsAuthorizationService.java
for f in "$SVC" \
         influora-api/src/test/java/com/influora/service/MetricsAuthorizationServiceTest.java \
         influora-api/src/test/java/com/influora/repository/CollaborationRepositoryWorkspaceCreatorQueryTest.java; do
  [ -f "$f" ] || { echo "VERDICT: broken — $f is missing"; exit 1; }
done
grep -q "findByWorkspaceIdAndCreatorId" "$SVC" || {
  echo "VERDICT: broken — the creator-agreed collaboration grant is gone; brands are locked out again (F-0901)"; exit 1; }

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
LOG=$(mktemp 2>/dev/null || echo "${TMPDIR:-/tmp}/f0901-gate.log")
( cd influora-api && mvn -o -B test \
    -Dtest='MetricsAuthorizationServiceTest,CollaborationRepositoryWorkspaceCreatorQueryTest,AnalyticsService*Test' \
    -Dsurefire.failIfNoSpecifiedTests=false ) > "$LOG" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  if grep -q "Tests run:.*Failures: [1-9]\|Tests run:.*Errors: [1-9]" "$LOG"; then
    grep -E "Tests run:|FAIL" "$LOG" | tail -8
    echo "VERDICT: broken — the F-0901 authorization tests fail"; exit 1
  fi
  tail -5 "$LOG"; echo "· mvn did not reach the tests (offline repo / compile break elsewhere) — unavailable"; exit 2
fi
grep -q "MetricsAuthorizationServiceTest" "$LOG" && grep -q "CollaborationRepositoryWorkspaceCreatorQueryTest" "$LOG" || {
  echo "· the named tests did not run — unavailable, never green"; exit 2; }
grep -E "^\[INFO\] Tests run: [0-9]+, Failures: 0, Errors: 0, Skipped: 0$" "$LOG" | tail -1
echo "VERDICT: proved — F-0901 creator-agreed consent rule holds"
exit 0
