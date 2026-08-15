#!/usr/bin/env bash
# gates/F-0050-static-view-components.sh — origin: F-0050 (components-created-in-render).
# The defect: BoardView/ListView/TimelineView defined INSIDE BrandPipelinePage's body and
# mounted as <BoardView/> JSX — a fresh component identity every parent re-render, so the
# whole subtree remounts and loses state. The fixed shape (verified 2026-08-15): lowercase
# render HELPERS (boardView/listView/timelineView) invoked as plain calls `boardView()` —
# no separate fiber identity, no remount. This gate fails if the defect shape returns.
set -u
cd "$(dirname "$0")/../.." || { echo "· cannot reach repo root — unavailable"; exit 2; }
F=src/pages/brand-pipeline.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
FAIL=0
# defect shape 1: a capitalized view component defined inside a function body (indented)
if grep -qE "^[[:space:]]+(function (BoardView|ListView|TimelineView)\b|const (BoardView|ListView|TimelineView) =)" "$F"; then
  echo "IN-RENDER COMPONENT DEFINITION returned:"; grep -nE "^[[:space:]]+(function (BoardView|ListView|TimelineView)\b|const (BoardView|ListView|TimelineView) =)" "$F"
  FAIL=1
fi
# defect shape 2: mounting them as JSX elements (only harmful with shape 1, but with
# module-scope definitions absent it means an undefined-component crash — fail either way)
if grep -vE "^[[:space:]]*(//|\*|\{/\*)" "$F" | grep -qE "<(BoardView|ListView|TimelineView)[ />]"; then
  echo "JSX MOUNT of view component present (non-comment line):"
  grep -nE "<(BoardView|ListView|TimelineView)[ />]" "$F" | grep -vE ":[[:space:]]*(//|\*|\{/\*)"
  FAIL=1
fi
# the fixed shape must actually exist — an empty file passes no gate
if ! grep -qE "(boardView|listView|timelineView)\(\)" "$F"; then
  echo "ABSENT: no view render-helper invocation found — the page's view switch has changed shape; re-inspect"
  FAIL=1
fi
[ $FAIL -eq 0 ] && echo "fixed shape holds: render helpers invoked as plain calls, no in-render component identity"
echo "NOT CHECKED: components created during render in OTHER files (this gate is one file); whether the helpers themselves are heavy enough to need memoization; runtime state-loss behaviour"
exit $FAIL
