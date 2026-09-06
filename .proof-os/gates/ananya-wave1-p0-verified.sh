#!/usr/bin/env bash
# gates/ananya-wave1-p0-verified.sh — closes F-0459, F-0460, F-0461, F-0462, F-0465, F-0636.
#
# origin: five P0 frontend findings. A fresh-context CTO review found FOUR were already fixed on
# this branch (F-0459, F-0460, F-0461, F-0462 — this wave added the missing regression tests the
# ledger's own missed_by fields called for) and one was a false finding as scoped (F-0465 — the
# websocket already sends the token as a WS subprotocol, never in the URL; confirmed independently
# by grepping influora-api for ANY websocket endpoint at all — there is none, so nothing server-side
# could even read a query-string token today). F-0636 (collapsed GET /users/me error states) was a
# real, newly-fixed defect.
#
# THE REVIEW ALSO FOUND TWO BLOCKERS before promotion, both fixed and independently re-verified
# by priya before this gate was written:
#   1. tsc --noEmit failed on an unused @ts-expect-error the wave's own new test introduced —
#      deleted, tsc now clean.
#   2. A REAL new leak the F-0459 fix exposed: api.auth.logout (the only code that clears
#      creator_token out of sessionStorage) is gated behind isApiLive() at both real call sites, so
#      in mock/demo mode a remember-me-off logout never cleared sessionStorage, and F-0459's fixed
#      auth guard (which now reads sessionStorage too) found the leftover token. Fixed at
#      auth-session.ts's clearCreatorSession — falsified: reverting the one added line failed the
#      new sessionStorage assertion for the right reason, restored, green.
#
# F-0634 and F-0635 were investigated and found to be FALSE findings (a genuine partial-merge PATCH
# for F-0634; a correct trust-the-response-not-a-reread for F-0635) but no dedicated regression test
# was written for either — they are NOT covered by this gate and remain open in the ledger rather
# than being closed without a falsifiable proof.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

FILES="src/__tests__/creator-protected-route.test.tsx src/components/brand/brand-layout-logout.test.tsx src/lib/__tests__/upload-real-endpoint.f0461.test.ts src/pages/__tests__/brand-settings-workspace-field-preservation.test.tsx src/pages/__tests__/brand-settings-account-phone-load-errors.test.tsx src/admin/services/websocket.f0465.test.ts src/lib/__tests__/clear-creator-session-sessionstorage.f0459.test.ts"
for f in $FILES; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

echo "· tsc --noEmit"
tsc_out=$(npx --no-install tsc --noEmit 2>&1); tsc_rc=$?
if [ $tsc_rc -ne 0 ]; then
  printf '%s\n' "$tsc_out" | head -20
  echo "VERDICT: broken — tsc fails (a P0 wave that does not typecheck is not proved)"
  exit 1
fi
echo "  tsc clean"

BUDGET="${PROOF_ANANYA_WAVE1_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
# shellcheck disable=SC2086
echo "· vitest: 7 files (budget ${BUDGET}s)"
out=$($TO node_modules/.bin/vitest run $FILES 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected none of the 7 files — unavailable"; exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — at least one of this wave's 7 target files failed"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  all 7 files green"

echo "VERDICT: aligned (proved) — F-0459 (auth guard + the sessionStorage logout-leak residual),"
echo "         F-0460 (real logout endpoint call), F-0461 (real upload path, no fabricated URL),"
echo "         F-0462 (workspace PATCH preserves untouched fields), F-0465 (token never in the WS"
echo "         URL) and F-0636 (distinguishable GET /users/me error states) each have a real,"
echo "         falsified test passing, and the project typechecks clean."
echo "NOT CHECKED: F-0634/F-0635 (investigated as false findings, deliberately excluded — no"
echo "             dedicated test exists for either); live-backend behaviour; the full test suite"
echo "             beyond these 7 files."
exit 0
