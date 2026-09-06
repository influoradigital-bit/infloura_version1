#!/usr/bin/env bash
# gates/F-0656-escrow-contract-scoped.sh — instance gate, closes F-0656.
#
# origin: F-0656 (collaboration-scoped-not-contract-scoped) — DealResponse.escrowFunded was derived
# from EscrowHoldRepository.hasEscrowForCollaboration, whose milestone branch filters only
# PaymentMilestone.collaborationId and never contractId. ContractService#amend creates fresh,
# UNFUNDED milestones for the new version while the original FUNDED hold stays bound to the
# superseded version's milestone (nothing refunds or re-links it). Once the amendment became
# current, the deal room reported it as funded off that untouched predecessor hold — telling both
# brand and creator that money was secured for a contract whose payment plan was never funded.
#
# THIS SHIPPED AS A "KNOWN GAP" WITH TESTS THAT ASSERTED THE BUG. The prior wave confirmed the
# defect and wrote characterization tests pinning escrowFunded == true, so the suite was green
# BECAUSE the defect existed. Inverting those was part of the fix. CHECK C exists specifically so
# that state cannot return unnoticed.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

REPO=influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java
SVC=influora-api/src/main/java/com/influora/service/DealService.java
JPA=influora-api/src/test/java/com/influora/repository/EscrowHoldRepositoryContractScopeTest.java
MOCK=influora-api/src/test/java/com/influora/service/DealServiceEscrowContractScopeTest.java
for f in "$REPO" "$SVC" "$JPA" "$MOCK"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

# --- CHECK A: the query is contract-scoped ------------------------------------------------------
grep -q "boolean hasEscrowForContract" "$REPO" || {
  echo "VERDICT: broken — hasEscrowForContract is gone (F-0656)"; exit 1; }
grep -q "AND m.contractId = :contractId" "$REPO" || {
  echo "VERDICT: broken — the milestone branch no longer filters m.contractId, so a hold under ANY"
  echo "         contract version satisfies it again — the exact F-0656 defect"; exit 1; }
echo "· hasEscrowForContract exists and filters m.contractId"

# --- CHECK B: DealService uses it, scoped to the RESOLVED current contract ----------------------
grep -q "hasEscrowForContract(" "$SVC" || {
  echo "VERDICT: broken — DealService no longer calls the contract-scoped query (F-0656)"; exit 1; }
grep -q "latest.getId()" "$SVC" || {
  echo "VERDICT: broken — DealService no longer passes the RESOLVED current contract id; scoping to"
  echo "         the wrong version is the same lie in a different direction (F-0656)"; exit 1; }
echo "· DealService scopes the check to the resolved current contract"

# --- CHECK C: the tests must not have reverted to asserting the bug -----------------------------
if grep -q "known gap F-0656" "$MOCK"; then
  echo "VERDICT: broken — the Mockito test again asserts escrowFunded == true as a 'known gap';"
  echo "         the suite would be green BECAUSE the defect exists (F-0656)"
  exit 1
fi
grep -q "testAmendedContractIsNotFundedByPredecessorsHold" "$JPA" || {
  echo "VERDICT: broken — the real-database test pinning the fix is gone (F-0656)"; exit 1; }
grep -q "testCollaborationScopedQueryIsTheDefect" "$JPA" || {
  echo "VERDICT: broken — the POSITIVE CONTROL is gone. Without it, a green pass could mean the fix"
  echo "         works OR that the scenario stopped reproducing the bug at all (F-0656)"; exit 1; }
echo "· tests assert the fix, and the positive control is present"

# --- CHECK D: behaviour, against a REAL database -------------------------------------------------
# The Mockito test proves DealService CALLS the right method; only the @DataJpaTest executes the
# JPQL. A missing predicate would leave every mocked test green — this project has already shipped
# a wrong column name that way.
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — structure checked, BEHAVIOUR NOT PROVED"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — BEHAVIOUR NOT PROVED"; exit 2; }
log=$(mktemp 2>/dev/null || echo "/tmp/f0656.$$")
( cd influora-api && mvn -o clean test \
    -Dtest=EscrowHoldRepositoryContractScopeTest,DealServiceEscrowContractScopeTest \
    -DfailIfNoTests=true ) >"$log" 2>&1
rc=$?
if grep -qE "Failed to delete .*target|Failed to clean project" "$log"; then
  echo "· maven-clean could not delete influora-api/target — a JVM holds it. UNAVAILABLE, not a"
  echo "  finding. Re-run once no java.exe holds the directory."; exit 2; fi
if grep -qE "No compiler is provided|Could not resolve dependencies|Non-resolvable|There is no POM" "$log"; then
  echo "· the build could not run offline — unavailable, NOT a pass"; tail -12 "$log"; exit 2; fi
if [ "$rc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0656 regression tests do not pass"
  grep -E "Tests run:|FAILURE!|expected" "$log" | head -12; exit 1; fi
grep -E "Tests run:.*(EscrowHoldRepositoryContractScope|DealServiceEscrowContractScope)" "$log" | head -3

echo "VERDICT: aligned (proved) — escrowFunded is contract-scoped: the query filters the milestone"
echo "         branch by m.contractId, DealService passes the resolved CURRENT contract id, and a"
echo "         real Hibernate/H2 test shows the same rows answering false for the unfunded"
echo "         amendment and true for the funded predecessor, with the old query's wrong answer"
echo "         kept executable as a positive control."
echo "NOT CHECKED: the direct e.collaborationId branch is deliberately NOT version-scoped — a hold"
echo "             carrying the collaboration id with no milestone (the pool path) was never bound"
echo "             to a contract version, and narrowing it would start reporting genuinely funded"
echo "             deals as unfunded. No data migration re-links or refunds holds already stranded"
echo "             on superseded milestones by amendments made BEFORE this fix: those deals now"
echo "             correctly read unfunded, which is the true state, but the money is still held"
echo "             against the old milestone and needs an operational sweep this gate does not do."
exit 0
