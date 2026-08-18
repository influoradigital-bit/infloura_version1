#!/usr/bin/env bash
# F-0293-consent-link-preserves-form.sh — gate for F-0293 (consent-link-destroys-form-state).
#
# brand-register.tsx step 2 and creator-register.tsx render "Terms of Service" / "Privacy
# Policy" as plain <a href="/terms">/<a href="/privacy"> with no `target`. This is a Vite SPA:
# a same-document navigation to /terms or /privacy tears down the whole React tree, discarding
# companyName/industry/teamSize/email/password/confirmPassword (brand) or
# name/email/password/confirmPassword (creator) — all live in-memory useState. A user who wants
# to read the Terms before agreeing loses the entire in-progress registration and lands back at
# step 1 (brand) or an empty form (creator).
#
# The F-0255 consent-links test (brand-register.consent-links.test.tsx) already proves the
# links are real <a href> elements, not dead buttons — that is NOT what this gate checks. This
# gate checks the one thing F-0255 explicitly left unchecked: that reaching the link does not
# cost the user their in-progress form. The fix must open the link without unloading the SPA
# document — target="_blank" rel="noopener noreferrer" is the cheap correct answer; an in-app
# modal or a state-preserving client route also satisfies this gate.
#
# Two legs, one per file (the identical defect exists in both registration forms):
#   1. brand-register.tsx — Terms/Privacy links must not be same-document navigations.
#   2. creator-register.tsx — same defect, same fix shape.
# Then a vitest leg that actually renders the forms, types into live fields, and asserts the
# links are not same-document navigations (so following them cannot unmount the form).
#
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

BRAND=src/pages/brand-register.tsx
BRAND_CODE=$(code_view "$BRAND") || { echo "$(code_why) - unavailable"; exit 2; }
CREATOR=src/pages/creator-register.tsx
CREATOR_CODE=$(code_view "$CREATOR") || { echo "$(code_why) - unavailable"; exit 2; }
for f in "$BRAND" "$CREATOR"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· brand-register.tsx: Terms/Privacy links are not same-document navigations"
# A same-document-navigation link is <a href="/terms" ...> with no target on the SAME line/tag.
# Match the specific anchor tags for /terms and /privacy and require target="_blank" on each.
brand_terms_line=$(grep -n 'href="/terms"' "$BRAND_CODE" | head -1)
brand_privacy_line=$(grep -n 'href="/privacy"' "$BRAND_CODE" | head -1)
if [ -z "$brand_terms_line" ] || [ -z "$brand_privacy_line" ]; then
  echo "  could not find Terms/Privacy links in $BRAND"
  echo "VERDICT: broken — consent links missing entirely in brand-register.tsx (F-0293)"
  exit 1
fi
if ! printf '%s' "$brand_terms_line" | grep -q 'target="_blank"'; then
  echo "  $brand_terms_line"
  echo "VERDICT: broken — brand-register.tsx Terms link has no target=\"_blank\"; following it"
  echo "         unmounts the in-progress registration form (F-0293)"
  exit 1
fi
if ! printf '%s' "$brand_privacy_line" | grep -q 'target="_blank"'; then
  echo "  $brand_privacy_line"
  echo "VERDICT: broken — brand-register.tsx Privacy link has no target=\"_blank\"; following it"
  echo "         unmounts the in-progress registration form (F-0293)"
  exit 1
fi
echo "  clean — both links carry target=\"_blank\""

echo "· creator-register.tsx: Terms/Privacy links are not same-document navigations"
creator_terms_line=$(grep -n 'href="/terms"' "$CREATOR_CODE" | head -1)
creator_privacy_line=$(grep -n 'href="/privacy"' "$CREATOR_CODE" | head -1)
if [ -z "$creator_terms_line" ] || [ -z "$creator_privacy_line" ]; then
  echo "  could not find Terms/Privacy links in $CREATOR"
  echo "VERDICT: broken — consent links missing entirely in creator-register.tsx (F-0293)"
  exit 1
fi
if ! printf '%s' "$creator_terms_line" | grep -q 'target="_blank"'; then
  echo "  $creator_terms_line"
  echo "VERDICT: broken — creator-register.tsx Terms link has no target=\"_blank\"; following it"
  echo "         unmounts the in-progress registration form (F-0293)"
  exit 1
fi
if ! printf '%s' "$creator_privacy_line" | grep -q 'target="_blank"'; then
  echo "  $creator_privacy_line"
  echo "VERDICT: broken — creator-register.tsx Privacy link has no target=\"_blank\"; following it"
  echo "         unmounts the in-progress registration form (F-0293)"
  exit 1
fi
echo "  clean — both links carry target=\"_blank\""

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/pages/__tests__/consent-link-preserves-form.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -30
  echo "VERDICT: broken — a consent link can still destroy in-progress registration state (F-0293)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — Terms/Privacy links no longer discard in-progress registration state"
echo "NOT CHECKED: real browser navigation behavior (jsdom never actually navigates on <a> click, so"
echo "             this gate proves via target/rel + a live-typed-field snapshot, not an actual click-"
echo "             through); an in-app modal or client-route fix would need this gate's assertions"
echo "             adjusted since it would no longer carry target=\"_blank\"."
exit 0
