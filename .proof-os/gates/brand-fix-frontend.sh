#!/usr/bin/env bash
# proof-os project gate — brand frontend wiring fixes
# Origin findings closed by this gate (RETENTION rule 3):
#   F-0070  billing checkout wired (initiateCheckout -> hosted checkout URL)
#   F-0071  onboarding workspace-slug availability check wired (checkSlug)
#   F-0072  command bar live-mode mock leak gated behind !isApiLive()
#   F-0073  billing cancel-subscription action wired (cancelSubscription)
#   F-0075  stale api.ts checkout/cancel comments corrected
#
# Green == the whole frontend still type-checks and the production bundle builds
# with these changes in place. It does NOT prove runtime HTTP behaviour (law 5):
# checkout/cancel live paths remain gated on provisioned Razorpay keys.
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "[brand-fix-frontend] tsc --noEmit"
npx tsc --noEmit
echo "[brand-fix-frontend] vite build"
npm run build
echo "[brand-fix-frontend] OK"
