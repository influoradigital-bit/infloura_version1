#!/usr/bin/env bash
# F-0262-settings-persistence-copy.sh — gate for F-0262 (stale-scope-disclaimer).
#
# src/pages/brand-settings.tsx's Notifications tab renders one disclaimer —
# "Settings sync isn't available yet — changes apply to this session only" — under a disabled
# "Save Preferences" button, and that disclaimer used to describe the WHOLE card. It does not:
# Email Notifications (handleEmailPrefChange), Campaign Alerts, and Bid Notifications
# (handleCategoryPrefChange) each call api.notifications.setPreference the moment the switch is
# flipped, in the same render as the disclaimer claiming nothing persists. Only Push
# Notifications and Weekly Digest are genuinely session-only (no backend to hit — both switches
# are `disabled`).
#
# Three legs:
#   1. The old blanket sentence is gone verbatim — it can no longer claim ALL changes on this
#      screen are ephemeral.
#   2. The three live-writing handlers are still present and still wired to their Switches — the
#      fix must correct the copy, not regress persistence to make the copy true.
#   3. The two genuinely-unwired controls (Push Notifications, Weekly Digest) still carry their
#      own honest "not available" caption — the fix must not delete the disclaimer outright and
#      leave those two looking wired when they are not.
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

PAGE=src/pages/brand-settings.tsx
[ -f "$PAGE" ] || { echo "· $PAGE missing — unavailable"; exit 2; }
PAGE_CODE=$(code_view "$PAGE") || { echo "$(code_why) - unavailable"; exit 2; }

fail=0

echo "· leg 1: the blanket 'changes apply to this session only' claim is gone"
# Exclude full-line comments so this checks actual rendered copy, not explanatory prose (e.g. a
# comment documenting the OLD disclaimer text for context would otherwise self-trigger this leg).
code_only=$(grep -vE '^\s*//' "$PAGE_CODE")
if printf '%s\n' "$code_only" | grep -qF "changes apply to this session only"; then
  printf '%s\n' "$code_only" | grep -nF "changes apply to this session only"
  echo "  still present in rendered/live code — three of the toggles on this screen PATCH the"
  echo "  server immediately, so this sentence is still lying to the brand about persistence"
  fail=1
fi
[ "$fail" -eq 0 ] && echo "  clean — blanket disclaimer no longer present in rendered/live code"

echo "· leg 2: the three live-writing handlers are still wired (copy fixed, not persistence regressed)"
for needle in "handleEmailPrefChange" "handleCategoryPrefChange" "api.notifications.setPreference"; do
  if ! grep -qF "$needle" "$PAGE_CODE"; then
    echo "  $needle missing — a real write path was removed instead of the stale copy"
    fail=1
  fi
done
# The three Switches must still call these handlers via onCheckedChange, not just reference
# them in a dead comment.
if ! grep -qE "onCheckedChange=\{handleEmailPrefChange\}" "$PAGE_CODE"; then
  echo "  Email Notifications switch no longer wired to handleEmailPrefChange"
  fail=1
fi
if ! grep -qE "onCheckedChange=\{\(e\) => handleCategoryPrefChange\('campaignAlerts', e\)\}" "$PAGE_CODE"; then
  echo "  Campaign Alerts switch no longer wired to handleCategoryPrefChange"
  fail=1
fi
if ! grep -qE "onCheckedChange=\{\(e\) => handleCategoryPrefChange\('bidNotifications', e\)\}" "$PAGE_CODE"; then
  echo "  Bid Notifications switch no longer wired to handleCategoryPrefChange"
  fail=1
fi
[ "$fail" -eq 0 ] && echo "  clean — all three persisted switches still call their live handlers"

echo "· leg 3: Push Notifications / Weekly Digest still carry their own honest 'not available' caption"
if ! grep -qE "Push notifications are not available yet" "$PAGE_CODE"; then
  echo "  Push Notifications lost its own honest caption"
  fail=1
fi
if ! grep -qE "Weekly digest isn't available yet" "$PAGE_CODE"; then
  echo "  Weekly Digest lost its own honest caption"
  fail=1
fi
[ "$fail" -eq 0 ] && echo "  clean — the two genuinely-unwired controls still say so individually"

if [ "$fail" -ne 0 ]; then
  echo "VERDICT: broken — disclaimer copy still misrepresents which controls persist (F-0262)"
  exit 1
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/pages/__tests__/brand-settings.disclaimer.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — settings persistence-copy test failed (F-0262)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — the disclaimer no longer claims persisted toggles are ephemeral,"
echo "         and the genuinely-unwired controls still disclose that individually"
echo "NOT CHECKED: General/Billing/Security tabs' own copy; whether setPreference's server-side"
echo "             write is actually durable (only that the client believes and states it is);"
echo "             wording quality beyond 'does not claim ephemerality for a persisted control'."
exit 0
