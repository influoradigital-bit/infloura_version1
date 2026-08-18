#!/usr/bin/env bash
# F-0261-command-bar-chord-and-shortcuts.sh — gate for F-0261 (unclosable-overlay).
#
# Two brand keydown listeners fight over the Cmd/Ctrl+K chord:
#   - src/components/brand/brand-layout.tsx (window-level, out of scope for this task) always
#     calls setCommandBarOpen(true) — never toggles closed.
#   - src/components/brand/command-bar.tsx (document-level, owned here) used to call
#     onOpenChange(!open) with no propagation guard.
# `document` is hit before `window` in the bubble phase, so on the CLOSING press
# command-bar's own listener computed `false` first, and then brand-layout's untouchable
# listener ran right after and forced it back to `true`. The bar opens but the same chord
# can never close it. Command-bar.tsx is the only file this task may edit, so the fix has to
# make its own listener authoritative by stopping the event before it reaches window — not by
# editing brand-layout.tsx.
#
# Separately: quickActions advertised ⌘C / ⌘F / ⌘W hints with no keydown handler behind any of
# them anywhere in the app. ⌘W closes the browser tab — the least acceptable one to ever wire
# up by accident.
#
# Separately again: navItems only pointed at 7 of the 15 real /brand/* destinations.
#
# Four legs:
#   1. The keydown handler stops propagation so brand-layout's window-level listener cannot
#      run after it for this chord.
#   2. No advertised-but-unimplemented shortcut remains, and Cmd+W specifically is never wired.
#   3. Every one of the 8 previously-missing destinations is present AND its route is real
#      (cross-checked against src/App.tsx, not just present in command-bar.tsx).
#   4. vitest: chord pressed twice closes it despite a conflicting window-level listener;
#      Escape closes it; no unimplemented shortcut hint renders.
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

BAR=src/components/brand/command-bar.tsx
APP=src/App.tsx
[ -f "$BAR" ] || { echo "· $BAR missing — unavailable"; exit 2; }
BAR_CODE=$(code_view "$BAR") || { echo "$(code_why) - unavailable"; exit 2; }
[ -f "$APP" ] || { echo "· $APP missing — unavailable"; exit 2; }
APP_CODE=$(code_view "$APP") || { echo "$(code_why) - unavailable"; exit 2; }

fail=0

echo "· leg 1: command-bar's own keydown handler stops propagation before brand-layout's window listener can run"
# Extract the keydown handler block (from the addEventListener('keydown' call back up to the
# nearest preceding "const down" / handler declaration) and require stopPropagation inside it.
if ! grep -qE "addEventListener\('keydown'" "$BAR_CODE"; then
  echo "  no document/window keydown listener found in command-bar.tsx at all"
  echo "VERDICT: broken — command-bar.tsx no longer owns the Cmd/Ctrl+K chord (F-0261)"
  exit 1
fi
handler_block=$(awk '/const (down|handleKeyDown|onKeyDown) *= *\(/{flag=1} flag{print} /addEventListener\(.keydown./{if(flag) exit}' "$BAR_CODE")
if ! printf '%s\n' "$handler_block" | grep -q "stopPropagation\|stopImmediatePropagation"; then
  echo "  keydown handler does not stop propagation — brand-layout's window-level listener"
  echo "  (unconditional setCommandBarOpen(true)) still runs right after it and re-opens the bar"
  fail=1
fi
[ "$fail" -eq 0 ] && echo "  clean — propagation is stopped inside the chord handler"

echo "· leg 2: no advertised-but-unimplemented keyboard shortcut, and Cmd+W is never wired"
# Exclude full-line comments so this checks actual code, not explanatory prose (e.g. a comment
# documenting why ⌘W is deliberately not wired would otherwise self-trigger this leg).
code_only=$(grep -vE '^\s*//' "$BAR_CODE")
if printf '%s\n' "$code_only" | grep -qE "⌘W|shortcut: *'W'|shortcut: *\"W\""; then
  echo "  Cmd+W is still advertised or implemented — that chord closes the browser tab"
  fail=1
fi
if printf '%s\n' "$code_only" | grep -qE "CommandShortcut" && printf '%s\n' "$code_only" | grep -qE "shortcut:"; then
  echo "  a quickAction still carries a 'shortcut' hint rendered via CommandShortcut with no"
  echo "  keydown handler behind it anywhere in the file"
  fail=1
fi
[ "$fail" -eq 0 ] && echo "  clean — no dangling shortcut hint, Cmd+W not wired"

echo "· leg 3: all 8 previously-missing destinations present in command-bar.tsx AND real in src/App.tsx"
declare -A ROUTES=(
  [pipeline]="/brand/pipeline"
  [analytics]="/brand/analytics"
  [reviews]="/brand/reviews"
  [disputes]="/brand/disputes"
  [messages]="/brand/messages"
  [meera]="/brand/meera"
  [help]="/brand/help"
  [notifications]="/brand/notifications"
)
for name in "${!ROUTES[@]}"; do
  route="${ROUTES[$name]}"
  if ! grep -qF "$route" "$BAR_CODE"; then
    echo "  $route ($name) — missing from command-bar.tsx navItems"
    fail=1
    continue
  fi
  if ! grep -qE "path=\"$route\"" "$APP_CODE"; then
    echo "  $route ($name) — command-bar links to it but src/App.tsx has no matching <Route path=\"$route\">"
    echo "  (a command that navigates to a 404 is worse than a missing one)"
    fail=1
  fi
done
[ "$fail" -eq 0 ] && echo "  clean — all 8 destinations present and route-verified"

if [ "$fail" -ne 0 ]; then
  echo "VERDICT: broken — command bar chord/shortcuts/destinations not fixed (F-0261)"
  exit 1
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/__tests__/command-bar.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — command bar behavior test failed (F-0261)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — the chord opens and closes the bar, no dead shortcut is"
echo "         advertised, and the 8 added destinations are real routes"
echo "NOT CHECKED: brand-layout.tsx's own listener still unconditionally forces true (it is out"
echo "             of scope for this task) — this gate only proves command-bar.tsx's own listener"
echo "             no longer loses the race to it; a live click-through of every new destination;"
echo "             mobile/touch command-bar entry points."
exit 0
