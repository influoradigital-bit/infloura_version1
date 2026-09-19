#!/usr/bin/env bash
# gates/f0964-public-profile-verified-only.sh — closes F-0964 (unverified-as-verified).
#
# The public creator page built "VerifiedMetrics" from the newest creator_metrics row of ANY
# data_source, and with no row fell back to CreatorProfile totals that include creator-declared
# platforms. Now: newest META_API row only (source-filtered finder + in-memory guard); with none,
# all four figures are omitted and the page shows "Not available yet" and claims no verification.
#
# Falsified 2026-09-19 (plus 4 kabir review rounds, 60+ mutants):
#   - profile-total fallback restored / engagement / verified_at=now / reach=0 -> service test red
#   - in-memory guard dropped / unfiltered finder                              -> service test red
#   - page: badge/pill/sr-only/title/heading/caption claiming verification     -> page test red
#   - page: !== null guard (omitted keys), truthy followers guard (real 0)     -> page test red
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "· not a git repo — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
TMP="$(mktemp -d)" || exit 2
git -C "$ROOT" archive HEAD | tar -x -C "$TMP" || { echo "· archive failed — unavailable"; exit 2; }
for t in declaredRowIsNotShownAsVerified; do
  grep -rq "$t" "$TMP/influora-api/src/test" || { echo "VERDICT: broken — regression test $t is gone"; exit 1; }
done
( cd "$TMP/influora-api" && mvn -o -B test -Dtest='PublicCreator*Test' -Dsurefire.failIfNoSpecifiedTests=false ) > "$TMP/mvn.log" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  if grep -qE "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$TMP/mvn.log"; then
    grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$TMP/mvn.log" | head -3
    echo "VERDICT: broken — F-0964 public profile service tests fail"; exit 1
  fi
  tail -5 "$TMP/mvn.log"; echo "· mvn did not reach the tests — unavailable"; exit 2
fi
if command -v cygpath >/dev/null 2>&1; then
  powershell.exe -NoProfile -Command "New-Item -ItemType Junction -Path '$(cygpath -w "$TMP/node_modules")' -Target '$(cygpath -w "$ROOT/node_modules")' | Out-Null" >/dev/null 2>&1
else
  ln -s "$ROOT/node_modules" "$TMP/node_modules"
fi
[ -d "$TMP/node_modules/vitest" ] || { echo "· node_modules not linkable — unavailable"; exit 2; }
( cd "$TMP" && npx vitest run src/pages/creator-verified-metrics.null-fields.test.tsx ) > "$TMP/vitest.log" 2>&1
RC=$?
CLEAN=$(sed 's/\x1b\[[0-9;]*m//g' "$TMP/vitest.log")
if [ $RC -ne 0 ]; then
  if echo "$CLEAN" | grep -qE "Tests +.*[0-9]+ failed"; then
    echo "$CLEAN" | grep -E "FAIL|Tests " | head -4
    echo "VERDICT: broken — F-0964 public page tests fail"; exit 1
  fi
  echo "$CLEAN" | tail -5; echo "· vitest did not reach the tests — unavailable"; exit 2
fi
echo "$CLEAN" | grep -qE "Tests +4 passed" || { echo "$CLEAN" | grep -E "Tests " ; echo "· expected 4 page tests — unavailable, never green"; exit 2; }
echo "VERDICT: proved — F-0964 public profile shows only Meta-verified figures"
exit 0
