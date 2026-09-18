#!/usr/bin/env bash
# F-0892-agency-converted.sh — gate for T-AGENCYCONVERT-0918 (lane C5).
# Closes F-0892: 4 AGENCY workspaces had no subscriptions row, were invisible to admin billing and
# never got the credit reset, and onboarding could still create more. Ruling:
# wiki/decisions/2026-09-18-agency-chooser-removed.md.
#
# LEGS:
#   1. The conversion migration exists and still converts AGENCY rows and inserts the Free row.
#   2. WorkspaceTest: Workspace#applyCompanyDetails never stores AGENCY (red with the guard
#      removed, falsified by Kabir and Vikram).
#   3. AdminBillingServiceTest: the admin "non-BRAND workspace rejected" guard is still exercised
#      by a pre-migration AGENCY row (red with that guard disabled, falsified by Kabir).
#
# exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

MIG=influora-api/src/main/resources/db/migration/V20260918140000__convert_agency_workspaces_to_brand.sql
[ -f "$MIG" ] || { echo "VERDICT: broken — $MIG is gone"; exit 1; }
grep -q "UPDATE workspaces SET type = 'BRAND' WHERE type = 'AGENCY'" "$MIG" \
  || { echo "VERDICT: broken — the migration no longer converts AGENCY rows"; exit 1; }
grep -q "INSERT INTO subscriptions" "$MIG" \
  || { echo "VERDICT: broken — the migration no longer gives converted workspaces a subscriptions row"; exit 1; }

# clean: an incremental build served a stale green on a mutant (F-0843).
out=$(cd influora-api && mvn -o clean test -Dtest="WorkspaceTest,AdminBillingServiceTest" -Dsurefire.failIfNoSpecifiedTests=false 2>&1)
rc=$?
if printf '%s\n' "$out" | grep -q "COMPILATION ERROR"; then
  printf '%s\n' "$out" | grep -A3 "COMPILATION ERROR" | head -20
  echo "VERDICT: broken — sources do not compile"; exit 1
fi
overall=$(printf '%s\n' "$out" | grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+ *$" | tail -1)
[ -n "$overall" ] || { printf '%s\n' "$out" | tail -30; echo "VERDICT: unavailable — no Tests-run summary"; exit 2; }
echo "$overall"
failures=$(printf '%s\n' "$overall" | sed -E 's/.*Failures: ([0-9]+).*/\1/')
errors=$(printf '%s\n' "$overall" | sed -E 's/.*Errors: ([0-9]+).*/\1/')
skipped=$(printf '%s\n' "$overall" | sed -E 's/.*Skipped: ([0-9]+).*/\1/')
run=$(printf '%s\n' "$overall" | sed -E 's/.*Tests run: ([0-9]+).*/\1/')
if [ "$rc" -ne 0 ] || [ "$failures" != "0" ] || [ "$errors" != "0" ]; then
  printf '%s\n' "$out" | grep -E "<<< (FAILURE|ERROR)" | tail -10
  echo "VERDICT: broken — the AGENCY guard or the admin non-BRAND guard test failed"; exit 1
fi
[ "$skipped" = "0" ] || { echo "VERDICT: broken — Skipped: $skipped"; exit 1; }
[ "${run:-0}" -ge 9 ] || { echo "VERDICT: unavailable — only $run tests ran, expected 9+"; exit 2; }

echo "VERDICT: proved — conversion migration present; no code path stores AGENCY; admin guard still tested"
echo
echo "NOT CHECKED:"
echo "  - The SQL on a real MySQL 8: nothing here can run it. It first runs for real in the A3 deploy."
echo "  - That production actually has 0 AGENCY rows afterwards: check with"
echo "    SELECT type, COUNT(*) FROM workspaces GROUP BY type after the deploy."
exit 0
