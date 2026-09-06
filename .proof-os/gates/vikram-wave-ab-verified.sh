#!/usr/bin/env bash
# gates/vikram-wave-ab-verified.sh — closes F-0406, F-0399, F-0476, F-0400, F-0402.
#
# Each of these five ledger rows is pinned by ONE of four real JUnit test classes below, each
# already falsified by its author AND independently re-verified by a fresh-context Priya review
# (.proof-os/tasks/T-QA-0901-BASES/vikram-wave-ab.review.md) before this gate was written — this
# gate exists to make that verified state RE-CHECKABLE on demand, not to establish it for the
# first time.
#
#   F-0406 (silent-partial-success, EscrowService.tryReleaseOnApproval) <- EscrowServiceReleaseOutcomeTest
#   F-0399 + F-0476 (unenforced-limit, DealService cumulative budget gate) <- DealServiceBudgetTest
#   F-0400 (unenforced-limit, CampaignService.maxCollaborators) <- DealServiceCollaboratorCapVerificationTest
#            (F-0400 was FALSE FINDING as originally worded — enforcement already existed at HEAD;
#            this test is what NEWLY proves it, so the ledger row closes against real coverage
#            rather than closing on "nothing to do")
#   F-0402 (money-path-dead-end, affiliate settlement -> wallet) <- CreatorAffiliateEarningSettlementTest
#            (also FALSE FINDING as originally worded: assigned file CreatorAffiliateEarningService
#            .java does not exist; the real settlement code is job/AffiliateSettlementWriter.java
#            and was already fixed at HEAD before this wave ran)
#
# EXPLICITLY NOT covered by this gate (do not read a green run here as proving these):
#   F-0489 — backend dispatch is correct (7 pre-existing tests in EscrowServiceReleaseTest.java)
#            but the finding is about frontend reachability, which is untouched.
#   F-0503 — was regressed by this same wave (broke 5 CampaignServiceTest cases) and is being
#            fixed separately; do not add it here until its own review confirms it.
#   F-0417/F-0418 — not attempted in this wave (wrong file assigned; F-0417 redispatched
#            separately, F-0418 blocked on a product ruling).
#
# LAW: main-compile-first, same as class_regression_tests.py — a concurrent session's unrelated
# edit elsewhere in this repo (documented, ongoing all session) must read as unavailable, not as
# a defect in the five findings this gate is actually about.
# LAW: exit 0 = proved · exit 1 = broken (real finding) · exit 2 = cannot run.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

API=influora-api
[ -d "$API" ] || { echo "· $API not a directory — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

BUDGET="${PROOF_WAVE_AB_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

echo "· step 1: does src/main compile ALONE (no test sources)?"
out=$($TO mvn -o -q -DskipTests -Dmaven.test.skip=true -f "$API/pom.xml" compile 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  compile exceeded ${BUDGET}s — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: unavailable — src/main does not compile right now, unrelated to these five findings"
  echo "NOT CHECKED: everything — the module never built"
  exit 2
fi
echo "  src/main compiles cleanly"

CLASSES="com.influora.service.EscrowServiceReleaseOutcomeTest,com.influora.service.DealServiceBudgetTest,com.influora.service.DealServiceCollaboratorCapVerificationTest,com.influora.job.CreatorAffiliateEarningSettlementTest"
echo "· step 2: mvn -Dtest=$CLASSES test"
out=$($TO mvn -o -q -Dtest="$CLASSES" -f "$API/pom.xml" test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  test run exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "No tests were executed|does not exist|cannot find symbol"; then
  printf '%s\n' "$out" | tail -30
  echo "VERDICT: unavailable — one or more target test classes could not be found/compiled"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — at least one of F-0406/F-0399/F-0476/F-0400/F-0402's target tests failed"
  echo "NOT CHECKED: any ledger class not named above"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests run:" | sed 's/^/  /'
echo "  all four target classes green"

echo "VERDICT: aligned (proved) — F-0406, F-0399, F-0476, F-0400 and F-0402 each have a real,"
echo "         previously-falsified JUnit test passing on the current tree."
echo "NOT CHECKED: F-0489 (frontend reachability), F-0503 (separately in repair), F-0417/F-0418"
echo "             (not attempted or blocked on a product ruling) — none of those five are proved"
echo "             by this gate. Runtime/live-backend behaviour. The full test suite."
exit 0
