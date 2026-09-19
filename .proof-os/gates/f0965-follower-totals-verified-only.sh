#!/usr/bin/env bash
# gates/f0965-follower-totals-verified-only.sh — closes F-0965 (unverified-as-verified, at the source).
#
# Ruling 2026-09-19 (option a + separate imported total): CreatorProfile.totalFollowers /
# engagementRate come from ONE rule (FollowerTotals): Meta-synced platforms only; else the imported
# one (labelled "imported, not verified" in discovery); else 0. Creator-declared platforms never
# count. platform_stats.source + creator_profiles.followers_source added and backfilled by
# V20260919100000; ScoreCalculationJob scores only META_API rows.
#
# Falsified 2026-09-19 (plus 2 kabir review rounds, 40+ mutants; migration also run on real MySQL 8):
#   rule counts declared / double-counts imported, each writer back to "sum everything", import
#   not tagged, scoring on the any-source finder, mapper drops followersSource, migration without
#   its metrics guard / Instagram filter / link filter / declared-only step / NONE guard, and the
#   discovery label missing from the grid, list or Featured row -> each turns a named test red.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "· not a git repo — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
TMP="$(mktemp -d)" || exit 2
git -C "$ROOT" archive HEAD | tar -x -C "$TMP" || { echo "· archive failed — unavailable"; exit 2; }
[ -f "$TMP/influora-api/src/main/resources/db/migration/V20260919100000__platform_stats_source_and_follower_provenance.sql" ] \
  || { echo "VERDICT: broken — the F-0965 migration is not committed"; exit 1; }
( cd "$TMP/influora-api" && mvn -o -B test \
    -Dtest='FollowerTotalsTest,CreatorMapperFollowersSourceTest,FollowerProvenanceMigrationBackfillTest,PlatformStatsAggregationJobTest,ExternalCreatorLinkServiceAdoptPlatformStatTest,PortfolioServiceTest,ScoreCalculationJobTest' \
    -Dsurefire.failIfNoSpecifiedTests=false ) > "$TMP/mvn.log" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  if grep -qE "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$TMP/mvn.log"; then
    grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$TMP/mvn.log" | head -5
    echo "VERDICT: broken — F-0965 backend tests fail"; exit 1
  fi
  tail -5 "$TMP/mvn.log"; echo "· mvn did not reach the tests — unavailable"; exit 2
fi
grep -q "FollowerProvenanceMigrationBackfillTest" "$TMP/mvn.log" || { echo "· migration test did not run — unavailable, never green"; exit 2; }
if command -v cygpath >/dev/null 2>&1; then
  powershell.exe -NoProfile -Command "New-Item -ItemType Junction -Path '$(cygpath -w "$TMP/node_modules")' -Target '$(cygpath -w "$ROOT/node_modules")' | Out-Null" >/dev/null 2>&1
else
  ln -s "$ROOT/node_modules" "$TMP/node_modules"
fi
[ -d "$TMP/node_modules/vitest" ] || { echo "· node_modules not linkable — unavailable"; exit 2; }
( cd "$TMP" && npx vitest run src/components/brand/discover/__tests__/creator-discovery-followers-source.test.tsx ) > "$TMP/vitest.log" 2>&1
RC=$?
CLEAN=$(sed 's/\x1b\[[0-9;]*m//g' "$TMP/vitest.log")
if [ $RC -ne 0 ]; then
  if echo "$CLEAN" | grep -qE "Tests +.*[0-9]+ failed"; then
    echo "$CLEAN" | grep -E "FAIL|Tests " | head -4
    echo "VERDICT: broken — F-0965 discovery label tests fail"; exit 1
  fi
  echo "$CLEAN" | tail -5; echo "· vitest did not reach the tests — unavailable"; exit 2
fi
echo "$CLEAN" | grep -qE "Tests +4 passed" || { echo "$CLEAN" | grep -E "Tests "; echo "· expected 4 label tests — unavailable, never green"; exit 2; }
grep -E "Tests run: [0-9]+, Failures: 0, Errors: 0, Skipped: [0-9]+$" "$TMP/mvn.log" | tail -1
echo "VERDICT: proved — F-0965 follower totals count only verified (or labelled imported) platforms"
exit 0
