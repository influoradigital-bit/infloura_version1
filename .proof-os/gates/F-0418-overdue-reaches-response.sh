#!/usr/bin/env bash
# gates/F-0418-overdue-reaches-response.sh — instance gate, closes F-0418.
#
# origin: F-0418 (derived-fact-never-surfaced) — CreatorDeliverableService.isOverdue existed and
# had exactly ONE consumer: a log.info inside submitForReview. toStatusResponse already put
# deadline/submittedAt on the wire but never called isOverdue, and DeliverableStatusResponse had
# no `overdue` field at all, so the one derived fact the method exists for reached no client. The
# javadoc's claim that it was "computed on every read" was simply false.
#
# WHAT THIS GATE REFUSES TO ACCEPT AS A FIX: the field existing on the DTO. That is the shape the
# original defect already had — plumbing present, nothing flowing through it. So CHECK C mutates
# nothing and instead relies on the regression test, which was falsified by hardcoding `false` at
# the call site: exactly one test failed, testGetStatusOverdueTrueOnResponse.
#
# LOCK NOTE (learned the hard way): on Windows a lingering JVM holds influora-api/target, and
# maven-clean-plugin then fails with "Failed to delete ... target". That is an UNAVAILABLE
# instrument, never a finding. Three probe runs were misread as real failures before this was
# understood, including one where the mutated AND restored arms both failed — a probe in which
# both arms fail proves nothing at all.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

SVC=influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java
DTO=influora-api/src/main/java/com/influora/web/dto/deliverable/CreatorDeliverableDtos.java
TEST=influora-api/src/test/java/com/influora/service/CreatorDeliverableServiceTest.java
for f in "$SVC" "$DTO" "$TEST"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

# --- CHECK A: the DTO carries the field --------------------------------------------------------
grep -qE "^\s*boolean overdue\)" "$DTO" || {
  echo "VERDICT: broken — DeliverableStatusResponse has no \`overdue\` field; the derived fact"
  echo "         cannot reach any client (F-0418)"; exit 1; }
echo "· DeliverableStatusResponse declares \`overdue\`"

# --- CHECK B: toStatusResponse actually CALLS isOverdue ----------------------------------------
# The defect was isOverdue having only a log.info consumer. A field fed by a literal would satisfy
# CHECK A and still be the original bug, so require the real call at the response site.
if ! grep -q "isOverdue(deliverable, LocalDate.now()));" "$SVC"; then
  echo "VERDICT: broken — toStatusResponse no longer computes isOverdue for the response; the"
  echo "         field is fed by something else (a literal or a stored value), which is the"
  echo "         original F-0418 shape"
  exit 1
fi
log_only=$(grep -c "isOverdue(" "$SVC")
echo "· toStatusResponse computes isOverdue on the response ($log_only isOverdue reference(s) total)"

# --- CHECK C: behaviour ------------------------------------------------------------------------
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — structure checked, BEHAVIOUR NOT PROVED"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — BEHAVIOUR NOT PROVED"; exit 2; }
grep -q "testGetStatusOverdueTrueOnResponse" "$TEST" || {
  echo "VERDICT: broken — the test pinning \`overdue\` on the RESPONSE is gone; a green run below"
  echo "         would prove only that the surviving tests pass (F-0418)"; exit 1; }

echo "· running CreatorDeliverableServiceTest (mvn -o clean test)"
log=$(mktemp 2>/dev/null || echo "/tmp/f0418.$$")
( cd influora-api && mvn -o clean test -Dtest=CreatorDeliverableServiceTest -DfailIfNoTests=true ) >"$log" 2>&1
rc=$?
if grep -qE "Failed to delete .*target|Failed to clean project" "$log"; then
  echo "· maven-clean could not delete influora-api/target — a JVM is holding it. UNAVAILABLE,"
  echo "  NOT a finding. Re-run once no java.exe holds the directory."
  exit 2
fi
if grep -qE "No compiler is provided|Could not resolve dependencies|Non-resolvable|There is no POM" "$log"; then
  echo "· the build could not run offline — unavailable, NOT a pass"; tail -12 "$log"; exit 2
fi
if [ "$rc" -ne 0 ]; then
  echo "VERDICT: broken — CreatorDeliverableServiceTest does not pass"
  grep -E "Tests run:|FAILURE!|ERROR\]" "$log" | head -12
  exit 1
fi
grep -E "Tests run:.*CreatorDeliverableServiceTest" "$log" | head -2

echo "VERDICT: aligned (proved) — \`overdue\` is computed by isOverdue at the response site and"
echo "         carried on DeliverableStatusResponse, so the derived fact reaches the client"
echo "         instead of only a server log line; the test pinning it is present and green."
echo "NOT CHECKED: BrandDeliverableService, deliberately — its reachable statuses all require"
echo "             SUBMITTED/RESUBMITTED, so submittedAt is always non-null there and isOverdue"
echo "             can never be true; adding the field would be dead code. Its"
echo "             DeliverableDetailResponse also has no deadline, a separate DTO change. This"
echo "             gate says nothing about whether any UI RENDERS \`overdue\` — reaching the"
echo "             response is not the same as reaching the creator's eyes."
exit 0
