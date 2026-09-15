#!/usr/bin/env bash
# F-0802-F-0803-brand-fee-publish-paths.sh — CLASS gate for false-green-gate-blind-spot,
# scoped to the brand publish fee.
#
# WHAT IT GUARDS. The brand-side publish fee is charged from TWO independent paths:
#   CampaignService#update(AuthPrincipal, String, CampaignPatchRequest)  — the PATCH /campaigns/{id} UI path
#   ConfirmLaunchExecutor#doExecute(...)                                 — Meera's confirm_launch tool
# ConfirmLaunchExecutor's own javadoc records that this already shipped broken once: an AI-driven
# launch charged a silent 0% fee while the brand-initiated path charged the real one. One publish
# path quietly charging nothing is a defect this codebase has HAD, not one it might have.
#
# WHY A THIRD GATE WAS NEEDED. EntitlementConformanceTest's RATE check credits BRAND_FEE_BPS when
# anybody outside BrandCampaignFeeService calls it, so deleting one of the two callers left it
# GREEN (its javadoc discloses this). The first fix for that — BrandFeePublishPathConformanceTest —
# then shipped the SAME shape of hole one level down (F-0802): it keyed on the bare method NAME, so
# deleting the call from the real signature and adding any same-named overload that called it was
# fully green while PATCH /campaigns/{id} charged nothing. That is three occurrences of "the wrong
# caller satisfies the assertion" in one codebase. F-0803 is the second hole in the same gate: it
# read target/classes with no freshness check and passed against source whose call had been deleted.
#
# WHAT THE JUNIT GATE NOW DOES (this script only runs it): resolves each entry point's REAL
# descriptor via Type.getMethodDescriptor, follows same-class call edges transitively with ASM from
# that exact signature, and refuses to score at all if a .class is older than its .java.
#
# HOW IT WAS PROVED — five mutations, each run alone, restored between:
#   M1 delete only the CampaignService call                          -> RED
#   M2 delete only the ConfirmLaunchExecutor call                    -> RED
#   M4 delete the real call + add a same-named overload that calls it -> RED   (GREEN before F-0802)
#   M5 delete from source, surefire:test with no compile              -> RED via the freshness check
#   M6 behaviour-preserving Extract Method                            -> GREEN (no false positive)
# M4 was additionally reproduced independently by the dispatcher, not only by the producer.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable-never-green.
set -u

ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· no influora-api/pom.xml here — unavailable"; exit 2; }

TESTS="BrandFeePublishPathConformanceTest,EntitlementConformanceTest,CampaignServiceTest,CampaignActivationGatesTest,ConfirmLaunchExecutorTest"
BUDGET="${PROOF_FEE_GATE_TIMEOUT:-900}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 30 $BUDGET"; else TO=""; fi

# test-compile FIRST and on its own. A failed compile makes surefire re-serve the PREVIOUS report,
# so a broken build reads as passing — that trap is recorded in this project's own notes.
echo "· mvn -o test-compile (budget ${BUDGET}s)"
cout=$(cd influora-api && $TO mvn -o -q test-compile 2>&1); crc=$?
if [ $crc -eq 124 ] || [ $crc -eq 137 ]; then
  echo "  test-compile exceeded ${BUDGET}s — unavailable, NOT a finding"; exit 2
fi
if [ $crc -ne 0 ]; then
  echo "  test-compile FAILED — cannot trust any surefire report from this tree"
  echo "$cout" | tail -20
  exit 2
fi

echo "· mvn -o test -Dtest=$TESTS"
out=$(cd influora-api && $TO mvn -o test -Dtest="$TESTS" -DfailIfNoTests=false 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"; exit 2
fi

line=$(printf '%s\n' "$out" | grep -E '^\[(INFO|ERROR)\] Tests run:' | tail -1)
[ -n "$line" ] || { echo "  no 'Tests run:' line — surefire produced no report; unavailable"; printf '%s\n' "$out" | tail -20; exit 2; }
echo "  $line"

# Read the Skipped count, never just the exit code: a Testcontainers class that never ran reports
# BUILD SUCCESS with a non-zero Skipped, which reads as coverage it does not have.
skipped=$(printf '%s\n' "$line" | sed -n 's/.*Skipped: \([0-9]*\).*/\1/p')
if [ "${skipped:-0}" != "0" ]; then
  echo "  Skipped=$skipped — at least one class did not run; that is not a green"
  exit 1
fi

if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E '<<< (FAILURE|ERROR)!' | head -10
  echo "VERDICT: broken — a publish path no longer reaches the brand fee charge, or a behavioural fee test failed."
  exit 1
fi

cat <<'EOT'
VERDICT: aligned (proved) — both brand publish paths still reach BrandCampaignFeeService
         .chargeOnPublish from their exact method signature, each asserted independently,
         and the scanned bytecode was verified newer than its source.
NOT CHECKED: that the fee is actually charged AT RUNTIME. This proves a call instruction is
             reachable from the entry point, not that control flow gets there — an
             always-false guard around a reachable call passes here. That proof lives in
             CampaignServiceTest / CampaignActivationGatesTest / ConfirmLaunchExecutorTest,
             which this gate runs alongside for exactly that reason. A THIRD publish path
             added later is also not covered: it needs its own named assertion.
EOT
exit 0
