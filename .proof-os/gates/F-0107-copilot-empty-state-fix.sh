#!/usr/bin/env bash
# F-0107 (CR-64) — DailySuggestionSection must route the no_suggestion_today
# (dismissed + no suggestion) case to SuggestionEmptyState instead of a blank
# page, without disturbing ready/loading/idle/error rendering. Re-runs the
# pinning regression suite live; exits 0 only if it's green.
#
# Usage: F-0107-copilot-empty-state-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/components/creator/copilot/DailySuggestionSection.test.tsx
