#!/usr/bin/env bash
# F-0106 (CR-63) — accountType propagation, and F-0116 (CR-103) — no false
# "Account connected" claim on connected:false. Re-runs the pinning regression
# suite live; exits 0 only if it's green.
#
# Usage: F-0106-F-0116-meta-callback-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/pages/creator-meta-callback.test.tsx
