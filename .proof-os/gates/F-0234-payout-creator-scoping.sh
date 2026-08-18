#!/usr/bin/env bash
# F-0234-payout-creator-scoping.sh — gate for F-0234 (cross-tenant-payout-leak).
#
# GET /wallet/payouts returned EVERY creator's payouts to any authenticated creator. The cause
# was one annotation: an @Query carrying no `where` clause sat on
#   PayoutRepository#findByCreatorUserIdOrderByCreatedAtDesc
# and an explicit @Query REPLACES Spring Data's derivation from the method name, so the
# creatorUserId argument was accepted and silently ignored. Three javadoc paragraphs and a
# service comment all described a scoping predicate that was not in the executed SQL.
#
# Two legs, because each catches a different way back in:
#   1. SQL — run PayoutRepositoryCreatorScopingTest, which executes real Hibernate against H2 and
#      asserts an unknown creator gets an empty page. This is the leg that actually proves the
#      where clause exists in the query that runs.
#   2. THE ANNOTATION — refuse any @Query on that method at all. The derived name IS the where
#      clause; a hand-written string can drift from it, and this defect is exactly that drift. A
#      future @Query that happens to be correct would still be refused, deliberately: the point
#      is that the guarantee stays structural.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

REPO=influora-api/src/main/java/com/influora/repository/PayoutRepository.java
[ -f "$REPO" ] || { echo "· $REPO missing — unavailable"; exit 2; }
REPO_CODE=$(code_view "$REPO") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· annotation: no @Query on findByCreatorUserIdOrderByCreatedAtDesc"
# The 6 lines above the method signature — an @Query would sit immediately before it.
ann=$(grep -B6 "Page<Payout> findByCreatorUserIdOrderByCreatedAtDesc" "$REPO_CODE" \
      | grep -E "^[[:space:]]*@(org\.springframework\.data\.jpa\.repository\.)?Query" || true)
if [ -n "$ann" ]; then
  printf '%s\n' "$ann"
  echo "VERDICT: broken — an @Query overrides name derivation on the payout-history query; if it"
  echo "         omits the creator predicate, every creator's payouts leak (F-0234)"
  exit 1
fi
echo "  clean — the method name derives the where clause"

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

echo "· mvn -o test PayoutRepositoryCreatorScopingTest (real SQL against H2)"
out=$(cd influora-api && mvn -q -o test -Dtest='PayoutRepositoryCreatorScopingTest' \
        -DfailIfNoSpecifiedTests=false 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "ERROR|Tests run" | tail -15
  echo "VERDICT: broken — the payout query no longer filters by creator (F-0234)"
  exit 1
fi
echo "  3/3 green — an unknown creator gets an empty page"

echo "VERDICT: aligned (proved) — GET /wallet/payouts is tenant-scoped in the SQL that executes"
echo "NOT CHECKED: every OTHER repository method for the same annotation/derivation mismatch — this"
echo "             gate covers the one method F-0234 was found on. Nothing here proves the endpoint"
echo "             above it authenticates correctly, only that the query filters; and H2 is not"
echo "             MySQL, so a dialect-specific difference in the generated SQL is out of scope."
exit 0
