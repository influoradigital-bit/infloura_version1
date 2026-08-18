#!/usr/bin/env bash
# gates/F-0240-campaign-type-reaches-payload.sh
# origin failure: F-0240 (silent-type-discard) — the picker captured selectedType but never
# passed it to CampaignForm, so "Direct Deal" silently created a STANDARD campaign, immutably.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }
P=src/pages/brand-new-campaign.tsx
C=src/components/brand/campaigns/campaign-form.tsx
[ -f "$P" ] && [ -f "$C" ] || { echo "· source files missing — unavailable"; exit 2; }
C_CODE=$(code_view "$C") || { echo "$(code_why) - unavailable"; exit 2; }
P_CODE=$(code_view "$P") || { echo "$(code_why) - unavailable"; exit 2; }
fail=0
if ! grep -q "campaignType: selectedType" "$P_CODE"; then
  echo "· the picked type is not handed to CampaignForm"; fail=1
fi
if ! grep -qE "campaignType\??:" "$C_CODE"; then
  echo "· CampaignFormData has no campaignType field"; fail=1
fi
if ! grep -q "campaignType: isEditing" "$C_CODE"; then
  echo "· campaignType is not in the submit payload (create-only)"; fail=1
fi
[ $fail -eq 1 ] && { echo "VERDICT: broken (F-0240 regressed)"; \
  echo "NOT CHECKED: the template-apply path (F-0251, still open), the backend enum mapping, or runtime behaviour"; exit 1; }
echo "VERDICT: aligned (proved)"
echo "NOT CHECKED: the template-apply path (F-0251, still open), the backend enum mapping, or runtime behaviour"
exit 0
