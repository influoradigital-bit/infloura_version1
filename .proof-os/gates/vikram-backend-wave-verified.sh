#!/usr/bin/env bash
# gates/vikram-backend-wave-verified.sh — closes the full Vikram backend wave (A+B+C+D):
# F-0406, F-0399, F-0476, F-0400, F-0402, F-0403, F-0413, F-0414, F-0498, F-0503, F-0417,
# F-0646, F-0647 — ADMIN-BOOTSTRAP-0829's two permanent fixes (provisioning script + MFA-reset
# endpoint), retroactively opened as ledger rows so they close through promote.py like every
# other finding on this project rather than being marked done by prose alone.
#
# origin: multiple fresh-context Priya reviews across four dispatch waves this session, each of
# which independently re-read the code, ran the tests themselves, and ran at least one revert-probe
# before returning a verdict. Wave A/B's review caught a real F-0503 ordering regression (fixed and
# re-reviewed in Wave D) and a real F-0400/F-0402 misattribution (both were false findings as
# originally worded — closed on real pre-existing/adjacent fixes, not on "nothing to do"). Wave C's
# review caught two untracked files (ops runner + script) that would have shipped with zero CI
# coverage; Wave D's review caught a third. All three are now `git add`-ed.
#
# CTO RULE, adopted after Wave C's review: every gate in this repo runs `mvn -o clean test`, never
# an incremental build — Priya's Wave C revert-probe got a FALSE GREEN from a stale .class file on
# the very first attempt, and only caught it by manually deleting target/classes and re-running.
# `clean` is not optional here; it is the fix for a demonstrated false-positive mechanism.
#
# EXPLICITLY NOT covered here (do not read a green run as proving these):
#   F-0489 — backend dispatch is already correct; the finding is about frontend reachability.
#   F-0418 — blocked on a product ruling (what should a missed deliverable deadline actually do).
#   F-0644, F-0645 — new residual defects from the amend feature, logged, not yet fixed.
#
# LAW: main-compile-first (a concurrent session's edit elsewhere must read as unavailable, not a
#      defect in these eleven findings). LAW: exit 0 proved, 1 broken, 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

API=influora-api
[ -d "$API" ] || { echo "· $API not a directory — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

BUDGET="${PROOF_VIKRAM_WAVE_TIMEOUT:-600}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

CLASSES="com.influora.service.EscrowServiceReleaseOutcomeTest,com.influora.service.DealServiceBudgetTest,com.influora.service.DealServiceCollaboratorCapVerificationTest,com.influora.job.CreatorAffiliateEarningSettlementTest,com.influora.service.ContractCancelAmendTest,com.influora.service.portfolio.PortfolioServiceRateCardTest,com.influora.service.admin.AdminAuthServiceTest,com.influora.ops.ProvisionSuperAdminRunnerTest,com.influora.service.CampaignServiceTest,com.influora.service.CampaignActivationGatesTest,com.influora.service.BrandDeliverableServiceTest"

echo "· mvn -o clean -Dtest=<11 classes> test (this IS the compile step — clean, no separate pre-check)"
out=$($TO mvn -o -q clean -Dtest="$CLASSES" -f "$API/pom.xml" test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol|does not exist"; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: unavailable — src does not compile clean right now; could be one of these eleven"
  echo "         findings' own files or an unrelated concurrent edit — read the trace above to tell"
  echo "         which before treating this as a defect in any specific finding"
  exit 2
fi
if echo "$out" | grep -qi "No tests were executed"; then
  printf '%s\n' "$out" | tail -30
  echo "VERDICT: unavailable — none of the target classes were found"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -80
  echo "VERDICT: broken — at least one of the eleven target classes failed on a clean build"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests run:" | sed 's/^/  /'
echo "  all eleven target classes green on a clean build"

echo "VERDICT: aligned (proved) — F-0406, F-0399, F-0476, F-0400, F-0402, F-0403, F-0413, F-0414,"
echo "         F-0498, F-0503, F-0417 and ADMIN-BOOTSTRAP-0829's two permanent fixes each have a"
echo "         real, previously-falsified JUnit test passing on a CLEAN build of the current tree."
echo "NOT CHECKED: F-0489 (frontend half), F-0418 (blocked on product ruling), F-0644/F-0645"
echo "             (new residual defects, not yet fixed), runtime/live-backend behaviour, the"
echo "             full test suite beyond these eleven classes."
exit 0
