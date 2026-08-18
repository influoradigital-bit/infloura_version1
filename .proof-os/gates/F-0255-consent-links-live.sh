#!/usr/bin/env bash
# F-0255-consent-links-live.sh — gate for F-0255 (dead-legal-control).
#
# brand-register.tsx's mandatory consent checkbox sits beside two
# "Terms of Service" / "Privacy Policy" controls rendered as
# `<button type="button">` with no onClick and no href — dead controls next
# to a checkbox that BLOCKS registration until it is checked. The user must
# agree to documents they cannot open.
#
# Real content exists in this repo: src/content/legal/{terms-of-service,
# privacy-policy}.md, rendered by LegalPage and routed at App.tsx as
# `/terms` and `/privacy`. creator-register.tsx already links to them
# correctly (`<a href="/terms">` / `<a href="/privacy">`). The fix for
# brand-register.tsx is to match that existing, real pattern — not invent a
# new route and not fake one.
#
# Three legs:
#   1. Static: brand-register.tsx no longer renders a dead
#      `<button type="button">…Terms of Service…</button>` /
#      `…Privacy Policy…</button>` pair — the controls carry a real href.
#   2. Static: those hrefs point at routes App.tsx actually serves
#      (/terms, /privacy), not a route that 404s.
#   3. Behavioral: a vitest suite renders BrandRegisterPage's step 2 and
#      asserts the Terms/Privacy controls are real, keyboard-focusable links
#      with href="/terms" and href="/privacy" (not asserting a synthetic
#      .click() — Radix/anchor dead-control checks in this repo must read
#      role/href, per the known .click() false-negative).
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

TARGET=src/pages/brand-register.tsx
APP=src/App.tsx
[ -f "$TARGET" ] || { echo "· $TARGET missing — unavailable"; exit 2; }
TARGET_CODE=$(code_view "$TARGET") || { echo "$(code_why) - unavailable"; exit 2; }
[ -f "$APP" ] || { echo "· $APP missing — unavailable"; exit 2; }
APP_CODE=$(code_view "$APP") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· static: real Terms/Privacy content exists in this repo (checked, not assumed)"
if [ -f src/content/legal/terms-of-service.md ] && [ -f src/content/legal/privacy-policy.md ] \
   && grep -q 'path="/terms"' "$APP_CODE" && grep -q 'path="/privacy"' "$APP_CODE"; then
  echo "  confirmed — src/content/legal/{terms-of-service,privacy-policy}.md exist and"
  echo "  App.tsx routes /terms and /privacy through LegalPage"
else
  echo "  NOT confirmed — /terms or /privacy route (or its content file) is missing from this"
  echo "  repo; a fix that links to them would 404. (gate does not fail this leg by itself —"
  echo "  it only records what it found; legs 1-3 below still gate on the ACTUAL fix chosen)"
fi

echo "· static: no dead <button type=\"button\"> for Terms of Service / Privacy Policy in $TARGET"
if grep -nE '<button[^>]*type="button"[^>]*>\s*Terms of Service\s*</button>' "$TARGET_CODE" >/dev/null 2>&1 || \
   grep -nE '<button[^>]*type="button"[^>]*>\s*Privacy Policy\s*</button>' "$TARGET_CODE" >/dev/null 2>&1; then
  grep -nE 'Terms of Service|Privacy Policy' "$TARGET_CODE"
  echo "VERDICT: broken — brand-register.tsx still renders Terms of Service / Privacy Policy as a"
  echo "         dead <button type=\"button\"> with no onClick and no href (F-0255)"
  exit 1
fi
echo "  clean — no dead button wraps the consent link copy"

echo "· static: Terms/Privacy controls carry real hrefs to routes App.tsx serves"
TERMS_HREF_OK=false
PRIVACY_HREF_OK=false
grep -nE 'href="/terms"' "$TARGET_CODE" >/dev/null 2>&1 && TERMS_HREF_OK=true
grep -nE 'href="/privacy"' "$TARGET_CODE" >/dev/null 2>&1 && PRIVACY_HREF_OK=true
if ! $TERMS_HREF_OK || ! $PRIVACY_HREF_OK; then
  echo "  terms href present: $TERMS_HREF_OK · privacy href present: $PRIVACY_HREF_OK"
  echo "VERDICT: broken — brand-register.tsx does not link Terms of Service / Privacy Policy to"
  echo "         the real /terms and /privacy routes (F-0255)"
  exit 1
fi
echo "  clean — href=\"/terms\" and href=\"/privacy\" both present"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/pages/__tests__/brand-register.consent-links.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — the Terms/Privacy controls are not reachable real links (F-0255)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — Terms of Service and Privacy Policy are real, focusable links"
echo "         to the routes this repo actually serves, not dead buttons"
echo "NOT CHECKED: whether /terms and /privacy render acceptable legal text — LegalPage marks"
echo "             both noindex as v0 drafts pending legal counsel review (see App.tsx comment"
echo "             near the route registration); this gate proves the control is reachable, not"
echo "             that its destination is publication-ready. Also not checked: opening the link"
echo "             in a NEW tab losing the in-progress registration form state, if that's the"
echo "             chosen UX — only a live run shows that."
exit 0
