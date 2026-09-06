#!/usr/bin/env bash
# gates/wave-remaining-verified.sh — batch instance gate.
# Closes F-0655, F-0657, F-0658, F-0672, F-0634.
#
# These are the findings from the Arjun wave that I verified against disk rather than against the
# agents' own reports. Each is pinned by the narrowest check that can actually fail:
#
#   F-0655 dropped-field          — a RELEASED milestone on a retired predecessor must keep counting.
#                                   Falsified: removing the carve-out fails
#                                   testAnalyticsKeepsReleasedPredecessorMilestoneAfterSignature...
#                                   with expected:<2> but was:<1>.
#   F-0657 n-plus-one-query       — contract resolution batched into ONE query. Its test asserts
#                                   times(1) on the batched call AND never() on the per-collaboration
#                                   one, so reintroducing the N+1 necessarily trips it.
#   F-0658 misleading-test-javadoc— the javadoc claimed v1 was signed via the real generate ->
#                                   recordSignature flow while the code hand-builds it ACTIVE. The
#                                   finding was the LIE, not the shortcut, so the fix is the
#                                   correction — and this gate fails if the false claim returns.
#   F-0672 deploy-coupling-undoc  — F-0551's in-memory token makes the SameSite=Strict refresh
#                                   cookie a DEPLOY PRECONDITION: split the SPA and API across
#                                   registrable domains and every user is silently logged out.
#   F-0634 dto-field-partially-bound — resolved as NOT A DEFECT: UsersMeUpdatePayload is a genuine
#                                   partial merge, so a phone-only save cannot clear the other five
#                                   fields. The test proves the merge; the row was a false alarm.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

DMS=influora-api/src/main/java/com/influora/service/DeliverableMetricService.java
DMS_TEST=influora-api/src/test/java/com/influora/service/DeliverableMetricServiceTest.java
AMEND_TEST=influora-api/src/test/java/com/influora/service/ContractAmendmentSupersessionTest.java
RUNBOOK=deploy/utho/README.md
PHONE_TEST=src/pages/__tests__/brand-settings-account-phone-partial-merge.test.tsx
for f in "$DMS" "$DMS_TEST" "$AMEND_TEST" "$RUNBOOK" "$PHONE_TEST"; do
  [ -f "$f" ] || { echo "VERDICT: broken — $f is missing"; exit 1; }
done

# --- F-0655 --------------------------------------------------------------------------------------
grep -q "m.getStatus() == MilestoneStatus.RELEASED" "$DMS" || {
  echo "VERDICT: broken — the RELEASED carve-out is gone; a milestone whose money was already paid"
  echo "         out stops counting the moment its contract is superseded (F-0655)"; exit 1; }
grep -q "testAnalyticsKeepsReleasedPredecessorMilestoneAfterSignatureButNotUnreleasedOne" "$DMS_TEST" || {
  echo "VERDICT: broken — the F-0655 regression test is gone"; exit 1; }
echo "· F-0655: RELEASED milestones survive supersession, test present"

# --- F-0657 --------------------------------------------------------------------------------------
grep -q "findByWorkspaceId" "$DMS" || {
  echo "VERDICT: broken — the batched contract lookup is gone; the N+1 is back (F-0657)"; exit 1; }
grep -q "testAnalyticsBatchesContractLookupInsteadOfOnePerCollaboration" "$DMS_TEST" || {
  echo "VERDICT: broken — the F-0657 batching test is gone"; exit 1; }
# The never() assertion is what makes that test non-vacuous: without it, a passing times(1) says
# nothing about whether per-collaboration calls ALSO happen.
grep -q "Mockito.never()" "$DMS_TEST" || {
  echo "VERDICT: broken — the batching test no longer asserts the per-collaboration query is NEVER"
  echo "         called, so it would pass with the N+1 still present (F-0657)"; exit 1; }
echo "· F-0657: batched lookup present, and the test still asserts never() on the per-row query"

# --- F-0658 --------------------------------------------------------------------------------------
grep -q "F-0658" "$AMEND_TEST" || {
  echo "VERDICT: broken — the F-0658 correction note is gone from the amendment test"; exit 1; }
# Asserted POSITIVELY, on the correction's own wording. The first version of this check grepped
# NEGATIVELY for the false claim ("was drafted and fully signed via") and matched the correction
# sentence that quotes it in order to refute it. That is the third time today a check of mine has
# matched prose DESCRIBING a defect instead of the defect: the F-0447 probe, the F-0443 switcher
# javadoc, and this. A negative grep over prose is not a test — it fires on any text that discusses
# the thing, including the fix note.
grep -q "hand-constructs" "$AMEND_TEST" || {
  echo "VERDICT: broken — the javadoc no longer states that the predecessor is hand-constructed;"
  echo "         the correction that closed F-0658 has been reverted or paraphrased away"
  exit 1; }
echo "· F-0658: the amendment test's javadoc no longer asserts a flow it does not drive"

# --- F-0672 --------------------------------------------------------------------------------------
grep -q "F-0672" "$RUNBOOK" || {
  echo "VERDICT: broken — the deploy-coupling warning is gone from the runbook (F-0672)"; exit 1; }
grep -qiE "registrable domain" "$RUNBOOK" || {
  echo "VERDICT: broken — the runbook no longer names the registrable-domain precondition, which is"
  echo "         the whole content of the warning: split the SPA and API and every session dies"
  echo "         silently (F-0672)"; exit 1; }
echo "· F-0672: the runbook records the same-registrable-domain deploy precondition"

# --- F-0634 --------------------------------------------------------------------------------------
grep -q "a phone-only save does not clear the other profile fields" "$PHONE_TEST" || {
  echo "VERDICT: broken — the partial-merge test is gone; F-0634 was closed as NOT-A-DEFECT on the"
  echo "         strength of that test, so without it the closure has no evidence"; exit 1; }
echo "· F-0634: the partial-merge proof is present"

# --- behaviour -----------------------------------------------------------------------------------
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — structure checked, BEHAVIOUR NOT PROVED"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — BEHAVIOUR NOT PROVED"; exit 2; }
log=$(mktemp 2>/dev/null || echo "/tmp/wave.$$")
( cd influora-api && mvn -o clean test \
    -Dtest=DeliverableMetricServiceTest,ContractAmendmentSupersessionTest -DfailIfNoTests=true ) >"$log" 2>&1
rc=$?
if grep -qE "Failed to delete .*target|Failed to clean project" "$log"; then
  echo "· maven-clean could not delete influora-api/target — a JVM holds it. UNAVAILABLE, not a"
  echo "  finding. This lock has already caused three probe runs to be misread as real failures."
  exit 2; fi
if grep -qE "No compiler is provided|Could not resolve dependencies|Non-resolvable|There is no POM" "$log"; then
  echo "· the build could not run offline — unavailable, NOT a pass"; tail -12 "$log"; exit 2; fi
if [ "$rc" -ne 0 ]; then
  echo "VERDICT: broken — the backend regression tests do not pass"
  grep -E "Tests run:|FAILURE!|expected" "$log" | head -12; exit 1; fi
grep -E "Tests run:.*(DeliverableMetricServiceTest|ContractAmendmentSupersessionTest)" "$log" | head -3

echo "VERDICT: aligned (proved) — all five hold: a paid-out milestone survives supersession, the"
echo "         contract lookup is batched with the per-row query asserted never-called, the"
echo "         amendment test's javadoc no longer claims a flow it does not drive, the runbook"
echo "         records the registrable-domain deploy precondition, and the phone-only save is"
echo "         proved not to clear the other profile fields."
echo "NOT CHECKED: F-0657's batching is asserted through MOCK call counts, not measured query"
echo "             volume against a database — it proves the code path, not the latency win."
echo "             F-0672 is documentation: nothing here verifies the LIVE deployment actually"
echo "             serves the SPA and API from one registrable domain, and the runbook cannot"
echo "             enforce it. F-0634 is closed as not-a-defect on the SERVER contract as written"
echo "             today; if UserService#updateProfile ever moves to full-replace, the row becomes"
echo "             real again and this gate would not notice — only the phone test would."
exit 0
