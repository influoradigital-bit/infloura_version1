#!/usr/bin/env bash
# F-0244-owner-self-lockout.sh — gate for F-0244 (owner-self-lockout).
#
# useWorkspaceVerification runs TWO queries: the workspace (`me`) and the caller's role,
# the latter by matching localStorage `brand_user_id` against workspaceMembers.list().
# `isLoading` was derived from the workspace query ALONE, and `canVerify` was a bare
# `memberRole === 'OWNER' || memberRole === 'ADMIN'`. So an UNRESOLVED role — still in
# flight, rejected, or with no `brand_user_id` in storage — was indistinguishable from a
# role that had resolved to a non-admin. Five consumers read `canVerify`, and two of them
# pass it straight through with no loading gate of their own, so the workspace OWNER was
# shown the terminal "Only an Owner or Admin can submit workspace verification. Ask one of
# them." The sole owner was told to go ask themselves, with no retry offered.
#
# The fix distinguishes the three states and fails OPEN: `canVerify` is true whenever the
# role is not positively resolved, matching the sibling useBrandBillingAccess which shares
# the same ['workspace','my-role'] cache key. Showing a CTA that the server may 403 is the
# correct trade against locking the only person who can act out of the flow — authorization
# is enforced server-side either way. That trade is the thing this gate protects.
#
# Three legs:
#   1. `isLoading` still accounts for the role query. If it reverts to me-only, consumers
#      that gate on it start rendering an unresolved role as fact again.
#   2. `canVerify` still fails open — the literal guard, not the comment above it.
#   3. The spec: pending, rejecting, missing-id, genuine-non-admin, genuine-owner.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

HOOK=src/hooks/brand/useWorkspaceVerification.ts
[ -f "$HOOK" ] || { echo "· $HOOK missing — unavailable"; exit 2; }

echo "· isLoading accounts for the role query, not the workspace query alone"
if ! grep -qE "isLoading:[^,]*role\.isLoading" "$HOOK"; then
  grep -nE "isLoading:" "$HOOK"
  echo "VERDICT: broken — isLoading no longer covers the role query (F-0244); an unresolved"
  echo "         role is once again reported as a settled one"
  exit 1
fi
echo "  clean — isLoading covers both queries"

echo "· canVerify fails open while the role is unresolved"
if ! grep -q "roleResolved" "$HOOK"; then
  echo "VERDICT: broken — the roleResolved discriminant is gone (F-0244); canVerify can no"
  echo "         longer tell 'not yet known' from 'known to be a non-admin'"
  exit 1
fi
if ! grep -qE "canVerify: *roleResolved *\?" "$HOOK"; then
  grep -nE "canVerify:" "$HOOK"
  echo "VERDICT: broken — canVerify no longer branches on roleResolved (F-0244); the sole"
  echo "         OWNER can be told to ask an admin again"
  exit 1
fi
echo "  clean — canVerify is gated on roleResolved and fails open"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/hooks/brand/__tests__/useWorkspaceVerification.test.ts
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — an unresolved role is reported as a permission denial (F-0244)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — an unresolved role is never rendered as a denial"
echo "NOT CHECKED: that the five consumers of canVerify actually RENDER a retry — the hook"
echo "             exposes retryRole() but no test mounts WorkspaceVerificationBanner,"
echo "             BrandVerificationPage, campaign-form, brand-new-hype-campaign or"
echo "             VerificationRequiredBox to prove any of them calls it; whether the server"
echo "             403s the fail-open CTA gracefully for a genuine non-admin whose role query"
echo "             failed — that path is server-side and untested here; and whether"
echo "             brand_user_id is written by the login flow at all, which is the upstream"
echo "             cause of the missing-id branch this gate only makes survivable"
