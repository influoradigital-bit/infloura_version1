#!/usr/bin/env bash
# gates/F-0050-static-view-components.sh — origin: F-0050 (components-created-in-render).
# The defect: BoardView/ListView/TimelineView defined INSIDE BrandPipelinePage's body and
# mounted as <BoardView/> JSX — a fresh component identity every parent re-render, so the
# whole subtree remounts and loses state. The fixed shape (verified 2026-08-15): lowercase
# render HELPERS (boardView/listView/timelineView) invoked as plain calls `boardView()` —
# no separate fiber identity, no remount. This gate fails if the defect shape returns.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$(dirname "$0")/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. This gate already TRIED to do that below, with a
# line-start-only filter (`^[[:space:]]*(//|\*|\{/\*)`) — the same naive filter that was
# reproduced failing on gates/F-0238: it strips a line that is ENTIRELY a comment and
# misses both a trailing same-line comment and a `{/* ... */}` on a line that also holds
# code. It was also applied to only ONE of this gate's three checks, so a comment
# mentioning `const BoardView =` or `boardView()` still moved the verdict.
. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }
F=src/pages/brand-pipeline.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "· $(code_why) — unavailable"; exit 2; }
FAIL=0
# defect shape 1: a capitalized view component defined inside a function body (indented)
if grep -qE "^[[:space:]]+(function (BoardView|ListView|TimelineView)\b|const (BoardView|ListView|TimelineView) =)" "$F_CODE"; then
  echo "IN-RENDER COMPONENT DEFINITION returned:"; grep -nE "^[[:space:]]+(function (BoardView|ListView|TimelineView)\b|const (BoardView|ListView|TimelineView) =)" "$F_CODE"
  FAIL=1
fi
# defect shape 2: mounting them as JSX elements (only harmful with shape 1, but with
# module-scope definitions absent it means an undefined-component crash — fail either way)
if grep -qE "<(BoardView|ListView|TimelineView)[ />]" "$F_CODE"; then
  echo "JSX MOUNT of view component present (non-comment line):"
  grep -nE "<(BoardView|ListView|TimelineView)[ />]" "$F_CODE"
  FAIL=1
fi
# the fixed shape must actually exist — an empty file passes no gate
if ! grep -qE "(boardView|listView|timelineView)\(\)" "$F_CODE"; then
  echo "ABSENT: no view render-helper invocation found — the page's view switch has changed shape; re-inspect"
  FAIL=1
fi
[ $FAIL -eq 0 ] && echo "fixed shape holds: render helpers invoked as plain calls, no in-render component identity"
echo "NOT CHECKED: components created during render in OTHER files (this gate is one file); whether the helpers themselves are heavy enough to need memoization; runtime state-loss behaviour"
exit $FAIL
