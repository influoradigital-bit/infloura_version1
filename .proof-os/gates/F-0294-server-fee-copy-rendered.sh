#!/usr/bin/env bash
# F-0294-server-fee-copy-rendered.sh — gate for F-0294 (server-copy-fetched-and-ignored).
#
# BrandPlatformFeeService.getCurrentFee (influora-api/.../BrandPlatformFeeService.java:35-36,58)
# returns a CEO-mandated `copy` field — the fixed literal "Platform fee (10%) — charged only
# when your campaign goes live." — as brand-facing disclosure text (leadership ruling item 5).
# brand-campaign-detail.tsx fetches it via api.wallet.brandPlatformFee(), types it on the
# `platformFee` state (`{ ...; copy: string } | null`), and never renders it — instead
# substituting a client-authored sentence ("Actual fees are charged on real spend at campaign
# publish.") that claims something the server copy does not say. Separately the page branches
# on `platformFee.source === 'GLOBAL_DEFAULT'` to imply a second, per-plan rate exists;
# BrandPlatformFeeService only ever constructs SOURCE_GLOBAL_DEFAULT, so that branch is dead
# and misleading.
#
# This gate asserts:
#   1. The fee block actually interpolates `platformFee.copy` into JSX (server copy reaches
#      the screen) — not just holds it in a type.
#   2. The client-invented substitute sentence is gone.
#   3. The unreachable per-plan-rate branch on `platformFee.source` is gone.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

PAGE=src/pages/brand-campaign-detail.tsx
[ -f "$PAGE" ] || { echo "· $PAGE missing — unavailable"; exit 2; }

fail=0

echo "· checking $PAGE for server-copy rendering (F-0294)"

# 1. `platformFee.copy` must actually be interpolated into JSX, not merely present as a type
#    annotation (e.g. inside the useState<...copy: string...> generic). Restrict the search to
#    JSX expression usage: `{platformFee.copy}` or `{platformFee?.copy}`, with optional
#    whitespace, optionally guarded (e.g. `{platformFee.copy ? ... : ...}` or
#    `{platformFee.copy && ...}` also count as long as `platformFee.copy` is the token used).
if ! grep -Eq '\{[[:space:]]*platformFee(\.|\?\.)copy\b' "$PAGE"; then
  echo "  no JSX usage of platformFee.copy / platformFee?.copy found"
  echo "VERDICT: broken — the server's copy field is fetched and typed but never reaches the"
  echo "         rendered page (F-0294)"
  fail=1
fi

# 2. The client-invented substitute sentence must be gone — it claims "on real spend", which
#    is not in BRAND_FEE_COPY and is not something the backend asserts anywhere.
if grep -q 'Actual fees are charged on real spend' "$PAGE"; then
  echo "  client-invented substitute sentence ('Actual fees are charged on real spend...')"
  echo "  is still present"
  echo "VERDICT: broken — a client-authored claim is standing in for the server's real"
  echo "         disclosure text (F-0294)"
  fail=1
fi

# 3. The unreachable per-plan-rate branch must be gone. BrandPlatformFeeService (LOOKUP,
#    T-BRANDMED-0817) only ever constructs SOURCE_GLOBAL_DEFAULT — no second source value is
#    ever produced — so a ternary implying a per-plan rate exists is dead code that misleads.
if grep -q "your plan" "$PAGE"; then
  echo "  a \"your plan's current rate\" branch is still present — that value is unreachable"
  echo "  (BrandPlatformFeeService only ever returns SOURCE_GLOBAL_DEFAULT)"
  echo "VERDICT: broken — dead branch implies per-plan fee rates that do not exist (F-0294)"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "  clean — platformFee.copy reaches JSX, the invented sentence is gone, and the dead"
echo "  per-plan-rate branch is gone"
echo "VERDICT: aligned (proved) — the fee block renders the server's real disclosure copy"
echo "         instead of a client-invented substitute (F-0294)"
echo "NOT CHECKED: this is a static source-shape check, not a render — it does not confirm"
echo "             platformFee.copy is non-empty at runtime for the current backend response,"
echo "             or that no CSS hides the rendered node; the fee-block vitest suite covers"
echo "             the actual rendered-text case. It also cannot see whether a future second"
echo "             `source` value would need new UI text — only that today's code does not"
echo "             fabricate one."
exit 0
