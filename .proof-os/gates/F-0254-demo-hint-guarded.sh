#!/usr/bin/env bash
# F-0254-demo-hint-guarded.sh — gate for F-0254 (demo-hint-unguarded).
#
# The brand onboarding wizard's email-OTP step (onboarding-steps.tsx) renders
# "Demo mode: use code 123456 to verify" unconditionally — it prints in a LIVE
# build too, on an unguarded onboarding route, telling a real user a bypass
# code that does not work against the live server. The correct pattern
# already exists twice in this repo (email-otp-gate.tsx, brand-kyc-prompt.tsx):
# wrap the hint in `{!isApiLive() && ( ... )}` so it renders only in mock
# builds.
#
# Two legs:
#   1. Static: the demo-hint copy in onboarding-steps.tsx must be reachable
#      only through an `isApiLive()` (or `!isApiLive()`) branch — not printed
#      unconditionally.
#   2. Behavioral: a vitest suite renders the OTP step with the module mocked
#      to `isApiLive() -> true` (live) and asserts the demo-hint text is NOT
#      in the document, then mocks it to `false` (mock) and asserts it IS.
#
# What it deliberately does NOT do: assert exact copy wording — copy is free
# to change; what must not happen is the hint rendering when the app is live.
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

TARGET=src/components/brand/onboarding/onboarding-steps.tsx
[ -f "$TARGET" ] || { echo "· $TARGET missing — unavailable"; exit 2; }
TARGET_CODE=$(code_view "$TARGET") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· static: demo-hint copy in $TARGET is gated behind isApiLive()"
# Find the line with the demo-hint copy, then check the nearest preceding
# conditional (within 5 lines above) mentions isApiLive.
HINT_LINE=$(grep -n "Demo mode: use code" "$TARGET_CODE" | head -1 | cut -d: -f1)
if [ -z "$HINT_LINE" ]; then
  echo "  demo-hint copy not found verbatim — copy may have changed, checking for isApiLive guard near 'Demo mode' generally"
  HINT_LINE=$(grep -n "Demo mode" "$TARGET_CODE" | head -1 | cut -d: -f1)
fi
if [ -z "$HINT_LINE" ]; then
  echo "VERDICT: broken — no demo-hint text found at all in $TARGET; cannot verify guard (F-0254)"
  exit 1
fi
START=$((HINT_LINE - 6))
[ $START -lt 1 ] && START=1
CONTEXT=$(sed -n "${START},${HINT_LINE}p" "$TARGET_CODE")
if ! printf '%s\n' "$CONTEXT" | grep -q "isApiLive()"; then
  echo "$CONTEXT"
  echo "VERDICT: broken — demo hint at $TARGET:$HINT_LINE has no isApiLive() guard in scope (F-0254)"
  exit 1
fi
echo "  clean — isApiLive() guard found within 6 lines above the demo-hint copy"

echo "· static: isApiLive is imported from @/lib/api in $TARGET"
if ! grep -qE "import \{[^}]*isApiLive[^}]*\} from '@/lib/api'" "$TARGET_CODE"; then
  echo "VERDICT: broken — isApiLive is referenced but not imported from @/lib/api in $TARGET (F-0254)"
  exit 1
fi
echo "  clean — isApiLive imported"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/onboarding/__tests__/onboarding-steps.demo-hint.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — the demo hint renders in a live build, or the guarded test suite fails (F-0254)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — the demo OTP hint only renders when the app is not live"
echo "NOT CHECKED: whether VITE_API_MODE=live is actually set in the real production build"
echo "             pipeline (this gate only proves the component branches correctly on"
echo "             isApiLive()'s return value, not that the env var is wired at deploy time)."
exit 0
