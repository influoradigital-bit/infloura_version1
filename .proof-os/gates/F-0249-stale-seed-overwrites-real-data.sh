#!/usr/bin/env bash
# F-0249-stale-seed-overwrites-real-data.sh — gate for F-0249 (stale-seed-overwrites-real-data).
#
# The workspace form was seeded unconditionally with mock values — 'Tech Brands Co.',
# 'admin@techbrands.in' — and then overwritten by GET /workspaces/me. On the happy path
# nobody noticed. On a failed fetch the catch branch set only an error flag and never
# touched the form state, while `workspaceInfoLoading` was cleared in .finally() regardless
# of outcome — so the Save button re-enabled itself with the mock seed still sitting in the
# inputs. One click PATCHed those fabricated values over the brand's real workspace name and
# billing email. PATCH /workspaces/me is full-replace, so this was a destructive write of
# invented data triggered by a READ failure: the worst shape a bug of this class can take.
#
# The fix separates "unloaded" from "unchanged". In live mode the fields start empty rather
# than seeded, a `workspaceInfoLoaded` flag flips only on a successful fetch, and Save is
# disabled until it does — guarded both on the control and again at the top of the submit
# handler. The PATCH stays a full-object write, deliberately: the payload is full-replace
# server-side, so submitting a partial object would CLEAR the omitted fields. That is only
# safe because Save is now unreachable until every field holds a server value or a user edit.
#
# Three legs, because any one of them alone restores the bug:
#   1. No unconditional mock seed — the seed is live-mode-gated.
#   2. The loaded flag still exists and still gates Save.
#   3. The spec: a rejected getMe() leaves no mock string in the DOM and Save disabled.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

FILE=src/pages/brand-settings.tsx
[ -f "$FILE" ] || { echo "· $FILE missing — unavailable"; exit 2; }

echo "· the mock seed is gated on mock mode, not unconditional"
if grep -qE "workspaceName: *'Tech Brands Co\.'" "$FILE"; then
  grep -nE "workspaceName: *'Tech Brands Co\.'" "$FILE"
  echo "VERDICT: broken — the workspace form is seeded with mock values unconditionally"
  echo "         again (F-0249); a failed load leaves them submittable"
  exit 1
fi
if ! grep -qE "liveApi \? '' : 'Tech Brands Co\.'" "$FILE"; then
  echo "VERDICT: broken — the live-mode seed guard is gone (F-0249)"
  exit 1
fi
echo "  clean — live mode starts empty"

echo "· Save is gated on a successful load"
if ! grep -q "workspaceInfoLoaded" "$FILE"; then
  echo "VERDICT: broken — the workspaceInfoLoaded flag is gone (F-0249); Save can no longer"
  echo "         tell 'loaded and unchanged' from 'never loaded'"
  exit 1
fi
if ! grep -qE "liveApi && \(!workspaceInfoLoaded \|\| workspaceInfoLoadError\)" "$FILE"; then
  grep -nE "workspaceInfoLoaded" "$FILE"
  echo "VERDICT: broken — Save is no longer disabled on an unloaded or errored workspace"
  echo "         (F-0249); a read failure can once more become a destructive write"
  exit 1
fi
echo "  clean — Save disabled until a real load succeeds"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/pages/__tests__/brand-settings.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — a failed workspace load leaves fabricated values submittable (F-0249)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — a read failure can no longer become a destructive write"
echo "NOT CHECKED: that PATCH /workspaces/me is genuinely full-replace — the decision to keep"
echo "             a full-object payload rests on the client-side type's doc comment, not on"
echo "             reading the Java handler, and if the server is actually partial-update the"
echo "             full payload is merely redundant rather than wrong; whether the Retry"
echo "             control recovers on a second failure or only the first; and every OTHER"
echo "             form on this settings page — only the workspace-information block was in"
echo "             scope, and the same seed-then-overwrite shape may exist elsewhere in the file"
