#!/usr/bin/env bash
# gates/F-0243-verification-copy-agrees.sh
# origin failure: F-0243 (contradictory-gating-copy) — the banner said verification was required
# to launch while the KYC prompt on the same screen called it optional and non-blocking.
# Backend truth: CampaignValidator.validateStatusForWorkspace blocks ACTIVE, never DRAFT, and
# Workspace.applyKycDecision does NOT auto-publish drafts.
set -u
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
K=src/components/brand/campaigns/brand-kyc-prompt.tsx
B=src/components/brand/WorkspaceVerificationBanner.tsx
[ -f "$K" ] && [ -f "$B" ] || { echo "· source files missing — unavailable"; exit 2; }
fail=0
# The prompt must not call verification optional or claim it won't block.
if grep -q "won.t block your campaign" "$K"; then
  echo "· the KYC prompt still claims verification won't block the campaign"; fail=1
fi
if grep -qE "·\s*optional" "$K"; then
  echo "· the KYC prompt badge still reads 'optional'"; fail=1
fi
if ! grep -q "required to publish" "$K"; then
  echo "· the KYC prompt does not state that verification is required to publish"; fail=1
fi
# Neither surface may claim verification auto-publishes drafts.
for f in "$K" "$B"; do
  if grep -qE "go live the moment|publish as soon as it clears|publishes? automatically" "$f"; then
    echo "· $f claims drafts publish automatically on approval — applyKycDecision does not do that"
    fail=1
  fi
done
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0243 regressed)"; \
  echo "NOT CHECKED: copy on brand-verification.tsx and BrandKycForm.tsx, whether the wording is understandable, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: copy on brand-verification.tsx and BrandKycForm.tsx, whether the wording is understandable, or runtime behaviour"
exit 0
