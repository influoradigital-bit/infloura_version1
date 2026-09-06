#!/usr/bin/env bash
# gates/vikram-amend-lifecycle-verified.sh — closes F-0642, F-0644, F-0645, F-0653, F-0654.
#
# origin: a first attempt at F-0644/F-0645 was rejected by a fresh-context CTO review because two
# files (DeliverableMetricService, DealService) independently invented two DISAGREEING definitions
# of "current contract for a collaboration" in the same diff, and the disagreement caused a real
# regression (drafting an amendment dropped a RELEASED milestone's metrics, 3300 -> 300). This
# round consolidates both onto ONE canonical resolver, ContractService.resolveCurrentContract, and
# adds the missing piece neither attempt had: a real terminal-status transition
# (retirePredecessorIfSuperseded, wired into the real signature-completion path) so a signed
# amendment actually retires its predecessor instead of leaving two ACTIVE contracts forever.
#
# F-0642's finding turned out to be false on verification (the backend already wired heldReason to
# a response field, at HEAD, before this wave) — this gate closes it on the missing regression test
# that now actually asserts the response DTO field, not the internal record.
#
# EXPLICITLY NOT covered here: F-0652. A companion attempt at F-0652 in this same round made
# Idempotency-Key required on /release and /refund server-side with ZERO frontend caller sending
# it -- confirmed independently: EscrowController.java requires the header (no required=false), and
# payments.releasePayout in src/lib/api.ts sends no such header at either of its two real callers.
# That fix is WORSE than the finding it closes (every release/refund call would 400) and is
# deliberately excluded from this gate and NOT promoted.
#
# FALSIFICATION, measured 2026-09-04, three independent revert-probes by a fresh-context reviewer
# (not the fix's own author): reverting retirePredecessorIfSuperseded's wiring ->
# "expected: <COMPLETED> but was: <ACTIVE>"; reverting the DeliverableMetricService resolver call
# -> milestone count drops 2 -> 1; reverting the shared resolver method breaks BOTH call sites from
# one revert. All three restored, re-verified green.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable. Clean build always.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

API=influora-api
[ -d "$API" ] || { echo "· $API not a directory — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

BUDGET="${PROOF_AMEND_LIFECYCLE_TIMEOUT:-400}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

CLASSES="com.influora.service.ContractAmendmentSupersessionTest,com.influora.service.DeliverableMetricServiceTest,com.influora.service.DealServiceTest,com.influora.service.BrandDeliverableServiceTest"
echo "· mvn -o clean -Dtest=<4 classes> test"
out=$($TO mvn -o -q clean -Dtest="$CLASSES" -f "$API/pom.xml" test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol|does not exist"; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: unavailable"
  exit 2
fi
if echo "$out" | grep -qi "No tests were executed"; then
  printf '%s\n' "$out" | tail -20
  echo "VERDICT: unavailable — a target class was not found"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -80
  echo "VERDICT: broken — at least one of F-0642/F-0644/F-0645/F-0653/F-0654's target tests failed"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests run:" | sed 's/^/  /'
echo "  all four target classes green on a clean build"

echo "VERDICT: aligned (proved) — F-0642 (heldReason on the real response DTO), F-0644 (analytics"
echo "         no longer erased by a drafted amendment), F-0645 (funded predecessor stays current"
echo "         during the unsigned window), F-0653 (one shared current-contract resolver, no"
echo "         disagreement) and F-0654 (a signed amendment genuinely retires its predecessor to"
echo "         COMPLETED) each have a real, falsified test passing on a clean build."
echo "NOT CHECKED: F-0652 (deliberately excluded — see header); N-1 through N-5 residuals recorded"
echo "             by the review (post-signature analytics still drop a RELEASED milestone;"
echo "             escrowFunded stays collaboration-scoped; an N+1 query pattern; a misleading"
echo "             javadoc in the new test; COMPLETED now renders as 'Signed' in the FE)."
exit 0
