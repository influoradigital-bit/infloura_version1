#!/usr/bin/env bash
# brand_pipeline_static_components.sh — gate for F-0050 (components-created-in-render).
# BoardView/ListView/TimelineView were converted from in-render <Component/> usage
# to render helpers, and CollaborationCard hoisted to module scope. eslint.config.js
# pins react-hooks/static-components to ERROR for src/pages/brand-pipeline.tsx, so if
# an in-render component definition returns, eslint reports it and this gate fails.
#   exit 0 = proved (no in-render components) · 1 = broken (regressed) · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
[ -f node_modules/.bin/eslint ] || { echo "· eslint not installed — unavailable"; exit 2; }

out=$(node_modules/.bin/eslint src/pages/brand-pipeline.tsx -f stylish 2>&1); rc=$?
if [ $rc -ge 2 ]; then echo "· eslint tool failure (exit $rc) — unavailable"; printf '%s\n' "$out" | tail -3; exit 2; fi

if printf '%s\n' "$out" | grep -qiE "static-components|create components during render"; then
  printf '%s\n' "$out" | grep -iE "static-components|create components" | head
  echo "FAIL: static-components regressed — an in-render component definition returned to brand-pipeline.tsx"
  exit 1
fi
echo "PASS: no in-render component definitions in brand-pipeline.tsx (static-components pinned to error, clean)"
echo "NOT CHECKED: runtime render — verified separately via a mock-mode board/list/timeline + filter/search walk"
exit 0
