#!/usr/bin/env bash
# F-0859-F-0860-F-0861-renewal-job.sh — gate for T-RENEWAL-S2-0917 (lane S2).
# Closes F-0859 (comps never expired), F-0860 (unpaid Pro extended without asking Razorpay)
# and F-0861 (Free rows swept into the Pro renewal path, a second credit reset).
#
# THE CLASS: SubscriptionRenewalResetJob renewed every ACTIVE row with a lapsed period, so it
# could not tell a missed webhook from a comp, an unpaid Pro, or a Free row.
#
# PROOF: the job and service tests, run from a clean build. Each done_when guard was falsified
# from a git archive of 0b7f263/6c13982: comp branch removed, Free branch removed,
# pending/halted made to renew, fetch failure made to renew (old path and in-place period
# advance) — every one turns a named test red. SubscriptionRenewalResetJobEmailDeliveryTest uses
# a real Spring context with an AFTER_COMMIT listener, so a publish outside a transaction is
# caught (a mocked ApplicationEventPublisher cannot see that drop).
#
# exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

TESTS="SubscriptionRenewalResetJobTest,SubscriptionRenewalResetJobEmailDeliveryTest,SubscriptionServiceTest"

# clean: an incremental build served a stale green on a mutant (F-0843).
out=$(cd influora-api && mvn -o clean test -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false 2>&1)
rc=$?

if printf '%s\n' "$out" | grep -q "COMPILATION ERROR"; then
  printf '%s\n' "$out" | grep -A3 "COMPILATION ERROR" | head -40
  echo "VERDICT: broken — sources do not compile"
  exit 1
fi

overall=$(printf '%s\n' "$out" | grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+ *$" | tail -1)
if [ -z "$overall" ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: unavailable — no Tests-run summary in mvn output"
  exit 2
fi
printf '%s\n' "$out" | grep -E "^\[(INFO|ERROR)\] Tests run:.* in com\.influora"
echo "$overall"

failures=$(printf '%s\n' "$overall" | sed -E 's/.*Failures: ([0-9]+).*/\1/')
errors=$(printf '%s\n' "$overall" | sed -E 's/.*Errors: ([0-9]+).*/\1/')
skipped=$(printf '%s\n' "$overall" | sed -E 's/.*Skipped: ([0-9]+).*/\1/')
run=$(printf '%s\n' "$overall" | sed -E 's/.*Tests run: ([0-9]+).*/\1/')

if [ "$rc" -ne 0 ] || [ "${failures:-1}" != "0" ] || [ "${errors:-1}" != "0" ]; then
  printf '%s\n' "$out" | grep -E "<<< (FAILURE|ERROR)" | tail -20
  echo "VERDICT: broken — a renewal-job guard test failed (see the named test above)"
  exit 1
fi
if [ "${skipped:-1}" != "0" ]; then
  echo "VERDICT: broken — Skipped: $skipped; a gate whose proof did not run proves nothing"
  exit 1
fi
if [ "${run:-0}" -lt 60 ]; then
  echo "VERDICT: unavailable — only $run tests ran; expected 60+ across the three classes"
  exit 2
fi

echo "VERDICT: proved — comp expiry, Razorpay-verified renewal and Free-period advance tests all green, 0 skipped"
echo
echo "NOT CHECKED:"
echo "  - The job against a real SubscriptionService and database: the job tests mock the service."
echo "  - Live Razorpay: fetchSubscription parsing is tested on a realistic JSON body, never a real call."
echo "  - Pro rows that cannot be verified still keep Pro (F-0876); the webhook controller still"
echo "    emails and invoices on a delivery it skips as stale (F-0880)."
echo "  - That the falsification above still holds after later edits: re-run it when the job changes."
exit 0
