#!/usr/bin/env bash
# F-0225-cancelled-collaboration-revives.sh — gate for F-0225 (status-blind-duplicate-guard).
#
# Withdrawing an application sets the collaboration CANCELLED; the row survives, and
# UNIQUE(campaign_id, creator_id) (V6) forbids a second one. A status-blind duplicate guard
# therefore banned the pair from that campaign forever — the creator could never re-apply and
# the brand could never re-invite. The fix revives the CANCELLED row in place.
#
# Reviving is only safe because CANCELLED is unreachable after a contract exists: it is written
# in exactly one place (DealService#doReject), gated on Collaboration#canReject(), an allowlist
# over pre-contract states with CONTRACT_PENDING as the documented cut line. This gate therefore
# holds TWO things, and the second is the one that matters most:
#
#   1. BEHAVIOUR — the revive tests: a CANCELLED prior row revives, a LIVE one still 409s, and
#      the withdrawn negotiation's agreed rate / usage rights never carry into the new attempt.
#   2. THE INVARIANT — canReject() must not grow a post-contract status. If it ever does, revive
#      becomes able to resurrect a deal with a signed contract and funded escrow behind it. No
#      behavioural test would catch that, because the tests mock the repository; only reading
#      canReject() itself does.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

ENTITY=influora-api/src/main/java/com/influora/domain/entity/Collaboration.java
[ -f "$ENTITY" ] || { echo "· $ENTITY missing — unavailable"; exit 2; }

# ---- 0. migration dialect -------------------------------------------------------------------
# This project is MySQL 8.0 (application.yml -> jdbc:mysql, MySQLDialect; docker-compose ->
# mysql:8.0). The first F-0225 migration was written in Postgres grammar and would have aborted
# Flyway on boot. NO mvn gate can catch that: the F-0225 suites are Mockito/POJO tests that never
# open a connection, so build.mvn.sh exits 0 on a migration that cannot run. This leg is the
# cheapest real check — a grammar scan over the whole migration directory, not just this file.
MIG=influora-api/src/main/resources/db/migration
if [ -d "$MIG" ]; then
  echo "· migration dialect: MySQL grammar across $MIG"
  # `-- ` comment lines are excluded: this very migration documents the Postgres grammar it was
  # rewritten away from, and a gate that fails on its own explanation of the bug is a gate nobody
  # can keep green. Only executable SQL is scanned.
  bad=$(grep -rniE "ALTER +COLUMN +[a-z_]+ +(SET|DROP) +NOT +NULL|CREATE +(UNIQUE +)?INDEX +IF +NOT +EXISTS|\bSERIAL\b|\bBOOLEAN +DEFAULT +TRUE\b|USING +GIN|::[a-z]+" "$MIG" 2>/dev/null \
        | grep -vE ':[0-9]+:[[:space:]]*--' || true)
  if [ -n "$bad" ]; then
    printf '%s\n' "$bad"
    echo "VERDICT: broken — a migration uses non-MySQL grammar; Flyway will abort and the app will not boot"
    echo "         (MySQL wants MODIFY COLUMN ... NOT NULL, and has no IF NOT EXISTS on CREATE INDEX)"
    exit 1
  fi
  echo "  clean — no Postgres/MariaDB-only grammar"
else
  echo "· $MIG missing — skipping the dialect leg"
fi

echo "· invariant: canReject() admits no post-contract status"
# The body of canReject(), from its signature to the closing brace.
body=$(awk '/public boolean canReject\(\)/,/^    \}/' "$ENTITY")
[ -n "$body" ] || { echo "· could not locate canReject() — unavailable"; exit 2; }

forbidden=$(printf '%s\n' "$body" | grep -oE "CONTRACT_PENDING|CONTRACTED|IN_PROGRESS|REVIEW_PENDING|REVISION_REQUESTED|COMPLETED" || true)
if [ -n "$forbidden" ]; then
  printf '%s\n' "$body"
  echo "  canReject() now admits: $(printf '%s' "$forbidden" | tr '\n' ' ')"
  echo "VERDICT: broken — canReject() admits a post-contract status, so Collaboration#revive can now"
  echo "         resurrect a deal with a contract/escrow behind it. Revive's safety argument is void."
  exit 1
fi
echo "  clean — pre-contract statuses only"

# ---- 2. the javadoc's own claim about who routes here -----------------------------------------
# CollaborationReviveService's class javadoc makes two falsifiable claims: that THREE named
# services route through it, and that ConfirmLaunchExecutor deliberately does not, keeping a
# skip-filter instead. The first version of that javadoc said "four ... so it is held here",
# which was false — only three had been migrated. The general stale-comment gate
# (ci/stale-comment-check.py) cannot catch that: its own NOT CHECKED line says it does not catch
# "a comment that cites live code and describes it wrongly", and a rule broad enough to would
# false-positive across the 551 {@code ClassName} mentions in this tree. So the claim is pinned
# HERE, where it is exact and costs nothing — if anyone migrates the fourth call site or unwires
# one of the three, this fails and the comment gets corrected with the code.
SVC=influora-api/src/main/java/com/influora/service
CLE=$SVC/meera/tool/ConfirmLaunchExecutor.java
echo "· javadoc claim: three services route here, ConfirmLaunchExecutor deliberately does not"
claim_broken=""
for f in CreatorCampaignService CreatorDiscoveryService DealService; do
  if ! grep -q "collaborationReviveService.reviveOrRefuse" "$SVC/$f.java" 2>/dev/null; then
    claim_broken="$claim_broken\n  $f no longer routes through reviveOrRefuse"
  fi
done
if [ -f "$CLE" ] && ! grep -q "existsByCampaignIdAndCreatorId" "$CLE" 2>/dev/null; then
  claim_broken="$claim_broken\n  ConfirmLaunchExecutor no longer holds the skip-filter the javadoc describes"
fi
if [ -n "$claim_broken" ]; then
  printf '%b\n' "$claim_broken"
  echo "VERDICT: broken — CollaborationReviveService's javadoc no longer describes the code."
  echo "         Fix the comment in the same change, not later (stale-comment class, F-0075/F-0150)."
  exit 1
fi
echo "  clean — the comment still describes the code"

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

echo "· mvn -o test (revive behaviour, the encumbrance refusals, all three duplicate guards)"
out=$(cd influora-api && mvn -q -o test \
        -Dtest='CollaborationReviveTest,CollaborationReviveServiceTest,CreatorCampaignServiceTest,CreatorDiscoveryServiceTest,DealServiceTest' \
        -DfailIfNoSpecifiedTests=false 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "ERROR|Tests run" | tail -20
  echo "VERDICT: broken — the revive path or a duplicate guard regressed (F-0225)"
  exit 1
fi
echo "  suites green"

echo "VERDICT: aligned (proved) — a CANCELLED collaboration revives, a live one still 409s,"
echo "         and canReject() still confines CANCELLED to pre-contract states"
echo "NOT CHECKED: the migration EXECUTED against a real MySQL — this gate scans grammar only, and"
echo "             NOTHING in this repo executes it either: pom.xml carries flyway-core/flyway-mysql"
echo "             but NO flyway-maven-plugin, so .github/workflows/flyway-validate.yml sets"
echo "             SPRING_FLYWAY_* vars no plugin reads, and the Testcontainers path skips without"
echo "             Docker. A statement that is valid MySQL but semantically wrong — an unpinned"
echo "             updated_at firing ON UPDATE CURRENT_TIMESTAMP, say — passes this gate AND passes"
echo "             CI; only a manual run against MySQL catches it. Whether a revived row behaves"
echo "             correctly end to end at runtime; the DealMessage history a revived room inherits"
echo "             (deliberate — the prior rejection message stays true and visible, but no test"
echo "             asserts how that reads to either party); concurrent revive-vs-revive, which"
echo "             relies on the same UNIQUE constraint the insert path does, not on a row lock."
exit 0
